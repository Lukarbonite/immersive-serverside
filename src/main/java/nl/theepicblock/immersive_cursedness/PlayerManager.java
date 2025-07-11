package nl.theepicblock.immersive_cursedness;

import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import nl.theepicblock.immersive_cursedness.objects.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlayerManager {
    private final IC_Config icConfig;
    private final ServerPlayerEntity player;
    private final CursednessServer cursednessServer;
    private final PortalManager portalManager;
    private final BlockCache blockCache = new BlockCache();
    private final Set<UUID> hiddenEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> flickerGuard = new ConcurrentHashMap<>();

    // For fake entities
    private final Map<UUID, Integer> realToFakeId = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> fakeToRealId = new ConcurrentHashMap<>();
    private final Set<UUID> shownFakeEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> fakeEntityFlickerGuard = new ConcurrentHashMap<>();
    private int nextFakeEntityId = -1000000;

    private static final int FLICKER_GUARD_TICKS = 5;

    private AsyncWorldView sourceView;
    private AsyncWorldView destinationView;
    private ServerWorld currentSourceWorld;

    // Data prepared on the main thread
    private List<Entity> nearbyEntities = new ArrayList<>();
    private List<Entity> destinationEntities = new ArrayList<>();
    private Collection<Portal> portalsToProcess = new ArrayList<>();


    public PlayerManager(ServerPlayerEntity player, IC_Config icConfig, CursednessServer cursednessServer) {
        this.player = player;
        this.icConfig = icConfig;
        this.cursednessServer = cursednessServer;
        this.portalManager = new PortalManager(player, icConfig);
    }

    public void tickMainThread(int tickCount) {
        ServerWorld sourceWorld = player.getWorld();

        // Update async world views if world changed
        boolean worldChanged = sourceWorld != this.currentSourceWorld;
        if (worldChanged || this.sourceView == null || this.destinationView == null) {
            this.currentSourceWorld = sourceWorld;
            this.sourceView = new AsyncWorldView(sourceWorld, true);
            this.destinationView = new AsyncWorldView(Util.getDestination(sourceWorld), true);
            purgeCache(); // This is safe, it queues a task
        }

        // Safely update portal list and entity list from the main thread
        if (tickCount % 30 == 0 || worldChanged) {
            portalManager.update(sourceView);
        }
        // Create snapshots for the async thread
        this.portalsToProcess = new ArrayList<>(portalManager.getPortals());
        this.nearbyEntities = getEntitiesInRange(sourceWorld);

        // Fetch entities from destination world near portal exits and create a new list
        List<Entity> newDestinationEntities = new ArrayList<>();
        if (this.destinationView != null && !this.portalsToProcess.isEmpty()) {
            ServerWorld destWorld = this.destinationView.getWorld();
            Set<UUID> addedUuids = new HashSet<>();
            for (Portal portal : this.portalsToProcess) {
                TransformProfile profile = portal.getTransformProfile();
                if (profile != null) {
                    Box destBox = new Box(profile.getTargetPos()).expand(icConfig.horizontalSendLimit + 20);
                    destWorld.getEntitiesByType(TypeFilter.instanceOf(Entity.class), destBox, (entity) -> {
                                boolean isThisPlayer = entity.getUuid().equals(player.getUuid());
                                if (!entity.isAlive() || isThisPlayer) {
                                    return false;
                                }
                                // Players don't "shouldSave", but we still want to see them.
                                return entity.shouldSave() || entity instanceof ServerPlayerEntity;
                            })
                            .forEach(entity -> {
                                if (addedUuids.add(entity.getUuid())) {
                                    newDestinationEntities.add(entity);
                                }
                            });
                }
            }
        }
        this.destinationEntities = newDestinationEntities; // Atomically replace the list
    }

    public void tickAsync(int tickCount) {
        if (!((PlayerInterface) player).immersivecursedness$getEnabled() || player.isSleeping()) {
            return;
        }

        flickerGuard.replaceAll((k, v) -> v - 1);
        flickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);
        fakeEntityFlickerGuard.replaceAll((k, v) -> v - 1);
        fakeEntityFlickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);

        ServerWorld sourceWorld = this.currentSourceWorld;
        if (sourceWorld == null) return; // Not ready yet

        if (player.hasPortalCooldown()) {
            ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);
            return;
        }

        List<FlatStandingRectangle> viewRects = new ArrayList<>();
        Chunk2IntMap blocksInView = new Chunk2IntMap();
        BlockUpdateMap blockUpdatesToSend = new BlockUpdateMap();
        List<Packet<?>> packetList = new ArrayList<>();
        Set<UUID> entitiesInCullingZone = new HashSet<>();

        BlockState atmosphereBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_CONCRETE : Blocks.NETHER_WART_BLOCK).getDefaultState();
        BlockState atmosphereBetweenBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_STAINED_GLASS : Blocks.RED_STAINED_GLASS).getDefaultState();

        boolean isNearPortal = false;
        var bottomOfWorld = sourceWorld.getBottomY();

        for (Portal portal : this.portalsToProcess) {
            if (portal.isCloserThan(player.getPos(), 8)) {
                isNearPortal = true;
            }

            TransformProfile transformProfile = portal.getTransformProfile();
            if (transformProfile == null) continue;

            FlatStandingRectangle portalRect = portal.toFlatStandingRectangle();
            viewRects.add(portalRect);

            BlockPos.iterate(portal.getLowerLeft(), portal.getUpperRight()).forEach(portalBlockPos -> {
                if (portalRect.contains(portalBlockPos)) {
                    BlockPos immutablePos = portalBlockPos.toImmutable();
                    blocksInView.increment(immutablePos);
                    BlockState newState = Blocks.AIR.getDefaultState();
                    BlockState cachedState = blockCache.get(immutablePos);
                    if (cachedState == null || !cachedState.equals(newState)) {
                        blockCache.put(immutablePos, newState);
                        blockUpdatesToSend.put(immutablePos, newState);
                    }
                }
            });

            Vec3d playerEyePos = player.getEyePos();
            double playerCoordinateOnAxis = Util.get(playerEyePos, portalRect.getAxis());
            double portalCoordinateOnAxis = portalRect.getOther();

            for (int i = 1; i < icConfig.portalDepth; i++) {
                FlatStandingRectangle positiveSideLayer = portalRect.expandAbsolute(portalCoordinateOnAxis + i, playerEyePos);
                FlatStandingRectangle negativeSideLayer = portalRect.expandAbsolute(portalCoordinateOnAxis - i, playerEyePos);

                viewRects.add(positiveSideLayer);
                viewRects.add(negativeSideLayer);

                FlatStandingRectangle layerToRender;
                if (playerCoordinateOnAxis > portalCoordinateOnAxis) {
                    layerToRender = negativeSideLayer;
                } else {
                    layerToRender = positiveSideLayer;
                }

                for (Entity entity : this.nearbyEntities) {
                    if (layerToRender.contains(entity.getPos())) {
                        entitiesInCullingZone.add(entity.getUuid());
                    }
                }

                layerToRender.iterateClamped(player.getPos(), icConfig.horizontalSendLimit, Util.calculateMinMax(sourceWorld, destinationView.getWorld(), transformProfile), (pos) -> {
                    double distSq = portal.getDistance(pos);
                    if (distSq > icConfig.squaredAtmosphereRadiusPlusOne) return;

                    BlockState newState;
                    BlockEntity newBlockEntity = null;

                    if (distSq > icConfig.squaredAtmosphereRadius) {
                        newState = atmosphereBlock;
                    } else if (distSq > icConfig.squaredAtmosphereRadiusMinusOne) {
                        newState = atmosphereBetweenBlock;
                    } else {
                        newState = transformProfile.transformAndGetFromWorld(pos, destinationView);
                        newBlockEntity = transformProfile.transformAndGetFromWorldBlockEntity(pos, destinationView);
                    }

                    if (pos.getY() == bottomOfWorld + 1) newState = atmosphereBetweenBlock;
                    if (pos.getY() == bottomOfWorld) newState = atmosphereBlock;

                    BlockPos immutablePos = pos.toImmutable();
                    blocksInView.increment(immutablePos);

                    BlockState cachedState = blockCache.get(immutablePos);
                    if (cachedState == null || !cachedState.equals(newState)) {
                        blockCache.put(immutablePos, newState);
                        blockUpdatesToSend.put(immutablePos, newState);

                        if (newBlockEntity != null) {
                            packetList.add(Util.createFakeBlockEntityPacket(newBlockEntity, immutablePos, sourceWorld));
                        }
                    }
                });
            }
        }

        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(isNearPortal);

        blockCache.purge(blocksInView, viewRects, (pos, cachedState) -> {
            BlockState originalState = sourceView.getBlock(pos);
            if (!originalState.equals(cachedState)) {
                blockUpdatesToSend.put(pos, originalState);
                BlockEntity originalBlockEntity = sourceView.getBlockEntity(pos);
                if (originalBlockEntity != null) {
                    packetList.add(Util.createFakeBlockEntityPacket(originalBlockEntity, pos, sourceWorld));
                }
            }
        });

        List<Entity> entitiesToHide = new ArrayList<>();
        List<Entity> entitiesToShow = new ArrayList<>();

        for (Entity entity : this.nearbyEntities) {
            UUID uuid = entity.getUuid();
            boolean shouldBeHidden = entitiesInCullingZone.contains(uuid);
            boolean isCurrentlyHidden = hiddenEntities.contains(uuid);
            boolean isFlickerGuarded = flickerGuard.containsKey(uuid);

            if (shouldBeHidden) {
                if (!isCurrentlyHidden && !isFlickerGuarded) {
                    entitiesToHide.add(entity);
                    hiddenEntities.add(uuid);
                }
            } else {
                if (isCurrentlyHidden) {
                    entitiesToShow.add(entity);
                    hiddenEntities.remove(uuid);
                    flickerGuard.put(uuid, FLICKER_GUARD_TICKS);
                }
            }
        }

        // Handle fake entities
        // Step 1: Find all entities directly visible through a portal
        Map<UUID, Entity> visibleRealEntities = new HashMap<>();
        Map<UUID, Portal> entityPortalContext = new HashMap<>();
        for (Entity realEntity : this.destinationEntities) {
            for (Portal portal : this.portalsToProcess) {
                TransformProfile transformProfile = portal.getTransformProfile();
                if (transformProfile == null) continue;
                Vec3d fakePos = transformProfile.untransform(realEntity.getPos());
                for (FlatStandingRectangle rect : viewRects) {
                    if (rect.contains(fakePos)) {
                        visibleRealEntities.put(realEntity.getUuid(), realEntity);
                        entityPortalContext.put(realEntity.getUuid(), portal);
                        break;
                    }
                }
                if (visibleRealEntities.containsKey(realEntity.getUuid())) break;
            }
        }

        // Step 2: Ensure that if a passenger is visible, its vehicle is too.
        boolean addedNew;
        do {
            addedNew = false;
            for (Entity passenger : new ArrayList<>(visibleRealEntities.values())) {
                if (passenger.hasVehicle()) {
                    Entity vehicle = passenger.getVehicle();
                    if (vehicle != null && !visibleRealEntities.containsKey(vehicle.getUuid())) {
                        visibleRealEntities.put(vehicle.getUuid(), vehicle);
                        entityPortalContext.put(vehicle.getUuid(), entityPortalContext.get(passenger.getUuid()));
                        addedNew = true;
                    }
                }
            }
        } while (addedNew);


        // Step 3: Generate spawn and update packets for all visible entities
        Set<UUID> visibleUuids = visibleRealEntities.keySet();
        Map<UUID, Packet<?>> fakeEntitySpawnPackets = new HashMap<>();
        Map<UUID, List<Packet<?>>> fakeEntityUpdatePackets = new HashMap<>();

        for (Entity realEntity : visibleRealEntities.values()) {
            UUID uuid = realEntity.getUuid();
            Portal portal = entityPortalContext.get(uuid);
            if (portal == null) continue;

            TransformProfile transformProfile = portal.getTransformProfile();
            Vec3d fakePos = transformProfile.untransform(realEntity.getPos());

            int fakeId = realToFakeId.computeIfAbsent(uuid, k -> {
                int newId = nextFakeEntityId--;
                fakeToRealId.put(newId, k);
                return newId;
            });
            float fakeYaw = transformProfile.untransformYaw(realEntity.getYaw());
            float fakeHeadYaw = transformProfile.untransformYaw(realEntity.getHeadYaw());

            fakeEntitySpawnPackets.put(uuid, new EntitySpawnS2CPacket(
                    fakeId, realEntity.getUuid(), fakePos.x, fakePos.y, fakePos.z,
                    realEntity.getPitch(), fakeYaw, realEntity.getType(), 0,
                    transformProfile.untransform(realEntity.getVelocity()), fakeHeadYaw));

            List<Packet<?>> updates = new ArrayList<>();
            updates.add(new EntityPositionS2CPacket(fakeId, new PlayerPosition(fakePos, Vec3d.ZERO, fakeYaw, realEntity.getPitch()), Collections.emptySet(), realEntity.isOnGround()));
            updates.add(new EntityVelocityUpdateS2CPacket(fakeId, transformProfile.untransform(realEntity.getVelocity())));
            List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getChangedEntries();
            if (trackedValues != null && !trackedValues.isEmpty()) {
                updates.add(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
            }
            fakeEntityUpdatePackets.put(uuid, updates);
        }

        List<Packet<?>> fakeEntityFinalPackets = new ArrayList<>();
        // Spawn new fake entities
        for (UUID uuid : visibleUuids) {
            List<Packet<?>> updates = fakeEntityUpdatePackets.get(uuid);
            if (updates == null) continue; // Should not happen with the new logic

            if (!shownFakeEntities.contains(uuid) && !fakeEntityFlickerGuard.containsKey(uuid)) {
                fakeEntityFinalPackets.add(fakeEntitySpawnPackets.get(uuid));
                fakeEntityFinalPackets.addAll(updates);
            } else if (shownFakeEntities.contains(uuid)) {
                fakeEntityFinalPackets.addAll(updates);
            }
        }

        // Add passenger linking packets
        for (UUID uuid : visibleUuids) {
            Entity realVehicle = visibleRealEntities.get(uuid);
            if (realVehicle != null && !realVehicle.getPassengerList().isEmpty()) {
                int[] visiblePassengerIds = realVehicle.getPassengerList().stream()
                        .map(Entity::getUuid)
                        .filter(visibleUuids::contains)
                        .mapToInt(realToFakeId::get)
                        .toArray();

                if (visiblePassengerIds.length > 0) {
                    int fakeVehicleId = realToFakeId.get(uuid);
                    PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
                    buf.writeVarInt(fakeVehicleId);
                    buf.writeIntArray(visiblePassengerIds);
                    fakeEntityFinalPackets.add(EntityPassengersSetS2CPacket.CODEC.decode(buf));
                }
            }
        }

        // Destroy old fake entities
        Set<UUID> toRemove = new HashSet<>(shownFakeEntities);
        toRemove.removeAll(visibleUuids);
        if (!toRemove.isEmpty()) {
            int[] idsToDestroy = toRemove.stream()
                    .mapToInt(uuid -> realToFakeId.getOrDefault(uuid, 0))
                    .filter(id -> id != 0)
                    .toArray();
            if (idsToDestroy.length > 0) {
                fakeEntityFinalPackets.add(new EntitiesDestroyS2CPacket(idsToDestroy));
            }
            toRemove.forEach(uuid -> fakeEntityFlickerGuard.put(uuid, FLICKER_GUARD_TICKS));
        }

        shownFakeEntities.clear();
        shownFakeEntities.addAll(visibleUuids);

        Set<UUID> allInRangeUuids = this.nearbyEntities.stream().map(Entity::getUuid).collect(Collectors.toSet());
        hiddenEntities.removeIf(uuid -> !allInRangeUuids.contains(uuid));
        flickerGuard.keySet().removeIf(uuid -> !allInRangeUuids.contains(uuid));

        cursednessServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;

            blockUpdatesToSend.sendTo(player);
            for (Packet<?> packet : packetList) {
                if (packet != null) player.networkHandler.sendPacket(packet);
            }

            for (Entity entity : entitiesToHide) {
                player.networkHandler.sendPacket(Util.createEntityHidePacket(entity.getId()));
            }

            for (Entity entity : entitiesToShow) {
                EntityTrackerEntry entry = new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {});
                entry.sendPackets(player, player.networkHandler::sendPacket);
            }

            for (Packet<?> packet : fakeEntityFinalPackets) {
                player.networkHandler.sendPacket(packet);
            }
        });
    }

    public void purgeCache() {
        if (cursednessServer == null) return;
        BlockUpdateMap updatesToSend = new BlockUpdateMap();
        final AsyncWorldView viewForLambda = this.sourceView != null ? this.sourceView : new AsyncWorldView(player.getWorld());

        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);

        blockCache.purgeAll((pos, cachedState) -> {
            BlockState originalState = viewForLambda.getBlock(pos);
            if (originalState != cachedState) {
                updatesToSend.put(pos, originalState);
            }
        });

        List<Entity> entitiesToShow = new ArrayList<>();
        if (!hiddenEntities.isEmpty()) {
            ServerWorld sourceWorld = player.getWorld();
            for (UUID uuid : hiddenEntities) {
                Entity entity = sourceWorld.getEntity(uuid);
                if (entity != null) {
                    entitiesToShow.add(entity);
                }
            }
            hiddenEntities.clear();
        }
        flickerGuard.clear();

        // Purge fake entities
        List<Integer> fakeIdsToDestroy = new ArrayList<>();
        if (!shownFakeEntities.isEmpty()) {
            for (UUID uuid : shownFakeEntities) {
                fakeIdsToDestroy.add(realToFakeId.get(uuid));
            }
        }
        shownFakeEntities.clear();
        fakeEntityFlickerGuard.clear();
        realToFakeId.clear();
        fakeToRealId.clear();


        cursednessServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;

            updatesToSend.sendTo(player);

            for (Entity entity : entitiesToShow) {
                EntityTrackerEntry entry = new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {});
                entry.sendPackets(player, player.networkHandler::sendPacket);
            }

            if (!fakeIdsToDestroy.isEmpty()) {
                player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(fakeIdsToDestroy.stream().mapToInt(i->i).toArray()));
            }
        });
    }

    @Nullable
    public TransformProfile getTransformProfileForBlock(BlockPos p) {
        for (Portal portal : this.portalsToProcess) {
            if (portal.getTransformProfile() != null && portal.isBlockposBehind(p, player.getEyePos())) {
                return portal.getTransformProfile();
            }
        }
        return null;
    }

    private List<Entity> getEntitiesInRange(ServerWorld world) {
        double range = icConfig.renderDistance * 16.0;
        return world.getEntitiesByType(
                TypeFilter.instanceOf(Entity.class),
                player.getBoundingBox().expand(range),
                (entity) -> !entity.equals(this.player) && entity.isAlive()
        );
    }
}
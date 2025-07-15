package nl.theepicblock.immersive_cursedness;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.item.ItemStack;
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
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import nl.theepicblock.immersive_cursedness.mixin.EntitySetHeadYawS2CPacketAccessor;
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

    // For debug entities
    private final List<Integer> debugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> debugEntityUuids = new HashMap<>();

    // For fake entities
    private final Map<UUID, Integer> realToFakeId = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> fakeToRealId = new ConcurrentHashMap<>();
    private final Set<UUID> shownFakeEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> fakeEntityFlickerGuard = new ConcurrentHashMap<>();
    private int nextFakeEntityId = -1000000;

    // For vehicle dismount grace period
    private final Map<UUID, UUID> lastTickVehicleMap = new ConcurrentHashMap<>();

    private static final int FLICKER_GUARD_TICKS = 5;

    private AsyncWorldView sourceView;
    private AsyncWorldView destinationView;
    private ServerWorld currentSourceWorld;

    // Data prepared on the main thread - marked volatile to ensure visibility from async thread
    private volatile List<Entity> nearbyEntities = new ArrayList<>();
    private volatile Map<UUID, Entity> destinationEntityMap = new HashMap<>();
    private volatile List<Portal> portalsToProcess = new ArrayList<>();
    private volatile Map<UUID, Entity> entitiesToUpdateOnMainThread = new HashMap<>();


    public PlayerManager(ServerPlayerEntity player, IC_Config icConfig, CursednessServer cursednessServer) {
        this.player = player;
        this.icConfig = icConfig;
        this.cursednessServer = cursednessServer;
        this.portalManager = new PortalManager(player, icConfig);
    }

    public void tickMainThread(int tickCount) {
        ServerWorld sourceWorld = player.getWorld();

        boolean worldChanged = sourceWorld != this.currentSourceWorld;
        if (worldChanged || this.sourceView == null || this.destinationView == null) {
            this.currentSourceWorld = sourceWorld;
            this.sourceView = new AsyncWorldView(sourceWorld, true);
            this.destinationView = new AsyncWorldView(Util.getDestination(sourceWorld), true);
            purgeCache();
        }

        if (tickCount % 30 == 0 || worldChanged) {
            portalManager.update(sourceView);
        }

        ArrayList<Portal> newPortals = new ArrayList<>(portalManager.getPortals());
        newPortals.sort(Comparator.comparing(Portal::getLowerLeft));
        this.portalsToProcess = newPortals;
        this.nearbyEntities = getEntitiesInRange(sourceWorld);

        Map<UUID, Entity> newDestinationEntities = new HashMap<>();
        if (this.destinationView != null && !this.portalsToProcess.isEmpty()) {
            ServerWorld destWorld = this.destinationView.getWorld();
            for (Portal portal : this.portalsToProcess) {
                TransformProfile profile = portal.getTransformProfile();
                if (profile != null) {
                    Box destBox = new Box(profile.getTargetPos()).expand(icConfig.horizontalSendLimit + 20);
                    destWorld.getEntitiesByType(TypeFilter.instanceOf(Entity.class), destBox, (entity) -> {
                                boolean isThisPlayer = entity.getUuid().equals(player.getUuid());
                                if (!entity.isAlive() || isThisPlayer) return false;
                                boolean isRiddenByPlayer = entity.getPassengerList().stream().anyMatch(p -> p instanceof PlayerEntity);
                                return entity.shouldSave() || entity instanceof ServerPlayerEntity || isRiddenByPlayer;
                            })
                            .forEach(entity -> newDestinationEntities.putIfAbsent(entity.getUuid(), entity));
                }
            }
        }
        this.destinationEntityMap = newDestinationEntities;

        Map<UUID, Entity> entitiesToUpdate = this.entitiesToUpdateOnMainThread;
        if (!entitiesToUpdate.isEmpty()) {
            for (Map.Entry<UUID, Entity> entry : entitiesToUpdate.entrySet()) {
                Entity realEntity = entry.getValue();
                Integer fakeId = realToFakeId.get(realEntity.getUuid());
                if (fakeId != null) {
                    List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getDirtyEntries();
                    if (trackedValues != null && !trackedValues.isEmpty()) {
                        player.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
                    }
                }
            }
        }
    }

    private void processPortalRendering(Portal portal, ServerWorld sourceWorld, List<FlatStandingRectangle> viewRects, Chunk2IntMap blocksInView, BlockUpdateMap blockUpdatesToSend, List<Packet<?>> packetList, Set<UUID> entitiesInCullingZone, List<Entity> nearbyEntities) {
        TransformProfile transformProfile = portal.getTransformProfile();
        if (transformProfile == null) return;

        final FlatStandingRectangle portalRect = portal.toFlatStandingRectangle();

        // Check if the player is on the same block plane as the portal. If so, cull rendering.
        int playerBlockCoordinate = (int)Math.floor(Util.get(player.getPos(), portalRect.getAxis()));
        int portalBlockCoordinate = (int)Math.round(portalRect.getOther());
        if (playerBlockCoordinate == portalBlockCoordinate) {
            return;
        }

        final Vec3d playerEyePos = player.getEyePos();
        final FlatStandingRectangle frustumShape = portal.getFrustumShape(playerEyePos);
        final ViewFrustum viewFrustum = new ViewFrustum(playerEyePos, frustumShape);

        final BlockState atmosphereBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_CONCRETE : Blocks.NETHER_WART_BLOCK).getDefaultState();
        final BlockState atmosphereBetweenBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_STAINED_GLASS : Blocks.RED_STAINED_GLASS).getDefaultState();
        final int bottomOfWorld = sourceWorld.getBottomY();

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

        // Cull any source-world entity that is inside the frustum where the destination world will be rendered.
        // This hides entities that are behind the portal, making way for the destination view,
        // while leaving entities between the player and the portal visible to obstruct the view.
        for (Entity entity : nearbyEntities) {
            if (viewFrustum.contains(entity.getPos())) {
                entitiesInCullingZone.add(entity.getUuid());
            }
        }

        double playerCoordinateOnAxis = Util.get(playerEyePos, portalRect.getAxis());
        double portalCoordinateOnAxis = portalRect.getOther();

        for (int i = 1; i < icConfig.portalDepth; i++) {
            FlatStandingRectangle positiveSideLayer = portalRect.expandAbsolute(portalCoordinateOnAxis + i, playerEyePos);
            FlatStandingRectangle negativeSideLayer = portalRect.expandAbsolute(portalCoordinateOnAxis - i, playerEyePos);
            viewRects.add(positiveSideLayer);
            viewRects.add(negativeSideLayer);

            FlatStandingRectangle renderLayer;
            FlatStandingRectangle cullLayer;

            if (playerCoordinateOnAxis > portalCoordinateOnAxis) {
                renderLayer = negativeSideLayer;
                cullLayer = positiveSideLayer;
            } else {
                renderLayer = positiveSideLayer;
                cullLayer = negativeSideLayer;
            }

            renderLayer.iterateClamped(player.getPos(), icConfig.horizontalSendLimit, Util.calculateMinMax(sourceWorld, destinationView.getWorld(), transformProfile), (pos) -> {
                if (!viewFrustum.contains(pos)) return;

                double distSq = portal.getDistance(pos);
                if (distSq > icConfig.squaredAtmosphereRadiusPlusOne) return;

                BlockPos immutablePos = pos.toImmutable();
                blocksInView.increment(immutablePos);

                BlockState newState;
                BlockEntity newBlockEntity = null;

                if (distSq > icConfig.squaredAtmosphereRadius) newState = atmosphereBlock;
                else if (distSq > icConfig.squaredAtmosphereRadiusMinusOne) newState = atmosphereBetweenBlock;
                else {
                    newState = transformProfile.transformAndGetFromWorld(pos, destinationView);
                    newBlockEntity = transformProfile.transformAndGetFromWorldBlockEntity(pos, destinationView);
                }

                if (pos.getY() == bottomOfWorld + 1) newState = atmosphereBetweenBlock;
                if (pos.getY() == bottomOfWorld) newState = atmosphereBlock;

                BlockState cachedState = blockCache.get(immutablePos);
                if (cachedState == null || !cachedState.equals(newState)) {
                    blockCache.put(immutablePos, newState);
                    blockUpdatesToSend.put(immutablePos, newState);
                    if (newBlockEntity != null) {
                        packetList.add(Util.createFakeBlockEntityPacket(newBlockEntity, immutablePos, sourceWorld));
                    }
                }
            });

            cullLayer.iterateClamped(player.getPos(), icConfig.horizontalSendLimit, new Util.WorldHeights(sourceView.getBottomY(), sourceView.getTopYInclusive()), (pos) -> {
                // Corrected Culling Check: Use containsInSidePlanes
                if (viewFrustum.containsInSidePlanes(pos.toCenterPos())) {
                    BlockPos immutablePos = pos.toImmutable();
                    blocksInView.increment(immutablePos);
                    BlockState newState = Blocks.AIR.getDefaultState();
                    BlockState cachedState = blockCache.get(immutablePos);
                    if (cachedState == null || !cachedState.equals(newState)) {
                        blockCache.put(immutablePos, newState);
                        blockUpdatesToSend.put(immutablePos, newState);
                    }
                }
            });
        }
    }

    public void tickAsync(int tickCount) {
        if (!((PlayerInterface) player).immersivecursedness$getEnabled() || player.isSleeping()) {
            return;
        }

        List<Entity> nearbyEntities = this.nearbyEntities;
        Map<UUID, Entity> destinationEntityMap = this.destinationEntityMap;
        List<Portal> portalsToProcess = this.portalsToProcess;

        flickerGuard.replaceAll((k, v) -> v - 1);
        flickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);
        fakeEntityFlickerGuard.replaceAll((k, v) -> v - 1);
        fakeEntityFlickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);

        ServerWorld sourceWorld = this.currentSourceWorld;
        if (sourceWorld == null) return;

        if (player.hasPortalCooldown()) {
            ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);
            return;
        }

        List<FlatStandingRectangle> viewRects = new ArrayList<>();
        Chunk2IntMap blocksInView = new Chunk2IntMap();
        BlockUpdateMap blockUpdatesToSend = new BlockUpdateMap();
        List<Packet<?>> packetList = new ArrayList<>();
        Set<UUID> entitiesInCullingZone = new HashSet<>();
        boolean isNearPortal = false;

        List<Vec3d> currentDebugPoints = new ArrayList<>();
        boolean debugEnabled = player.getWorld().getGameRules().getBoolean(ImmersiveCursedness.PORTAL_DEBUG);

        for (Portal portal : portalsToProcess) {
            if (portal.isCloserThan(player.getPos(), 8)) {
                isNearPortal = true;
            }
            processPortalRendering(portal, sourceWorld, viewRects, blocksInView, blockUpdatesToSend, packetList, entitiesInCullingZone, nearbyEntities);

            if (debugEnabled) {
                final Vec3d playerEyePos = player.getEyePos();
                final FlatStandingRectangle frustumShape = portal.getFrustumShape(playerEyePos);
                currentDebugPoints.add(frustumShape.getTopLeft());
                currentDebugPoints.add(frustumShape.getTopRight());
                currentDebugPoints.add(frustumShape.getBottomLeft());
                currentDebugPoints.add(frustumShape.getBottomRight());
            }
        }

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

        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(isNearPortal);

        List<Entity> entitiesToHide = new ArrayList<>();
        List<Entity> entitiesToShow = new ArrayList<>();
        for (Entity entity : nearbyEntities) {
            UUID uuid = entity.getUuid();
            boolean shouldBeHidden = entitiesInCullingZone.contains(uuid) || fakeEntityFlickerGuard.containsKey(uuid);
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

        Map<UUID, Entity> visibleRealEntities = new HashMap<>();
        Map<UUID, Portal> entityPortalContext = new HashMap<>();
        for (Entity realEntity : destinationEntityMap.values()) {
            for (Portal portal : portalsToProcess) {
                TransformProfile transformProfile = portal.getTransformProfile();
                if (transformProfile == null) continue;
                ViewFrustum viewFrustum = new ViewFrustum(player.getEyePos(), portal.getFrustumShape(player.getEyePos()));
                if (viewFrustum.contains(transformProfile.untransform(realEntity.getPos()))) {
                    visibleRealEntities.put(realEntity.getUuid(), realEntity);
                    entityPortalContext.put(realEntity.getUuid(), portal);
                    break;
                }
            }
        }

        boolean addedNew;
        do {
            addedNew = false;
            for (Entity passenger : new ArrayList<>(visibleRealEntities.values())) {
                if (passenger.hasVehicle()) {
                    Entity vehicle = passenger.getVehicle();
                    if (vehicle != null && !visibleRealEntities.containsKey(vehicle.getUuid())) {
                        Entity vehicleEntity = destinationEntityMap.get(vehicle.getUuid());
                        if (vehicleEntity != null) {
                            visibleRealEntities.put(vehicle.getUuid(), vehicleEntity);
                            entityPortalContext.put(vehicle.getUuid(), entityPortalContext.get(passenger.getUuid()));
                            addedNew = true;
                        }
                    }
                }
            }
        } while (addedNew);

        for (Map.Entry<UUID, UUID> entry : this.lastTickVehicleMap.entrySet()) {
            UUID passengerUuid = entry.getKey();
            UUID vehicleUuid = entry.getValue();
            if (!visibleRealEntities.containsKey(vehicleUuid)) {
                Entity passenger = visibleRealEntities.get(passengerUuid);
                if (passenger != null && (passenger.getVehicle() == null || !passenger.getVehicle().getUuid().equals(vehicleUuid))) {
                    Entity vehicleEntity = destinationEntityMap.get(vehicleUuid);
                    if (vehicleEntity != null) {
                        visibleRealEntities.put(vehicleUuid, vehicleEntity);
                        entityPortalContext.put(vehicleUuid, entityPortalContext.get(passengerUuid));
                    }
                }
            }
        }
        this.lastTickVehicleMap.clear();
        for (Entity entity : visibleRealEntities.values()) {
            if (entity.hasVehicle()) {
                this.lastTickVehicleMap.put(entity.getUuid(), entity.getVehicle().getUuid());
            }
        }

        Set<UUID> visibleUuids = visibleRealEntities.keySet();
        Map<UUID, Packet<?>> fakeEntitySpawnPackets = new HashMap<>();
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
            fakeEntitySpawnPackets.put(uuid, new EntitySpawnS2CPacket(fakeId, realEntity.getUuid(), fakePos.x, fakePos.y, fakePos.z, realEntity.getPitch(), fakeYaw, realEntity.getType(), 0, transformProfile.untransformVector(realEntity.getVelocity()), fakeHeadYaw));
        }

        List<Packet<?>> fakeEntityFinalPackets = new ArrayList<>();
        Set<UUID> entitiesToActuallyShow = new HashSet<>();
        Set<UUID> entitiesToActuallyDestroy = new HashSet<>();
        for (UUID uuid : visibleUuids) {
            if (shownFakeEntities.contains(uuid)) {
                entitiesToActuallyShow.add(uuid);
            } else {
                if (!fakeEntityFlickerGuard.containsKey(uuid)) {
                    entitiesToActuallyShow.add(uuid);
                }
            }
        }
        for (UUID uuid : shownFakeEntities) {
            if (!visibleUuids.contains(uuid)) {
                entitiesToActuallyDestroy.add(uuid);
            }
        }

        for (UUID uuid : entitiesToActuallyShow) {
            if (!shownFakeEntities.contains(uuid)) {
                fakeEntityFinalPackets.add(fakeEntitySpawnPackets.get(uuid));
            }
        }

        for (UUID uuid : entitiesToActuallyShow) {
            boolean isNew = !shownFakeEntities.contains(uuid);
            Entity realEntity = visibleRealEntities.get(uuid);
            Portal portal = entityPortalContext.get(uuid);
            if (portal == null) continue;
            TransformProfile transformProfile = portal.getTransformProfile();
            int fakeId = realToFakeId.get(uuid);
            Vec3d fakePos = transformProfile.untransform(realEntity.getPos());
            Vec3d fakeVel = transformProfile.untransformVector(realEntity.getVelocity());
            float fakeYaw = transformProfile.untransformYaw(realEntity.getYaw());
            fakeEntityFinalPackets.add(new EntityPositionS2CPacket(fakeId, new PlayerPosition(fakePos, fakeVel, fakeYaw, realEntity.getPitch()), Collections.emptySet(), realEntity.isOnGround()));
            float fakeHeadYaw = transformProfile.untransformYaw(realEntity.getHeadYaw());
            byte headYawByte = (byte) MathHelper.floor(fakeHeadYaw * 256.0F / 360.0F);
            EntitySetHeadYawS2CPacket headYawPacket = new EntitySetHeadYawS2CPacket(realEntity, (byte)0);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setEntityId(fakeId);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setHeadYaw(headYawByte);
            fakeEntityFinalPackets.add(headYawPacket);

            if (isNew && realEntity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) realEntity;
                List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    equipmentList.add(Pair.of(slot, livingEntity.getEquippedStack(slot)));
                }
                if (!equipmentList.isEmpty()) {
                    fakeEntityFinalPackets.add(new EntityEquipmentUpdateS2CPacket(fakeId, equipmentList));
                }
            }
            if (isNew) {
                List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getChangedEntries();
                if (trackedValues != null && !trackedValues.isEmpty()) {
                    fakeEntityFinalPackets.add(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
                }
            }
        }

        for (UUID uuid : entitiesToActuallyShow) {
            Entity realVehicle = visibleRealEntities.get(uuid);
            if (realVehicle != null && realVehicle.getType() != EntityType.ITEM && !realVehicle.getPassengerList().isEmpty()) {
                int[] visiblePassengerIds = realVehicle.getPassengerList().stream().map(Entity::getUuid).filter(entitiesToActuallyShow::contains).mapToInt(pUuid -> realToFakeId.getOrDefault(pUuid, 0)).filter(id -> id != 0).toArray();
                if (visiblePassengerIds.length > 0) {
                    int fakeVehicleId = realToFakeId.get(uuid);
                    PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
                    buf.writeVarInt(fakeVehicleId);
                    buf.writeIntArray(visiblePassengerIds);
                    fakeEntityFinalPackets.add(EntityPassengersSetS2CPacket.CODEC.decode(buf));
                }
            }
        }

        if (!entitiesToActuallyDestroy.isEmpty()) {
            int[] idsToDestroy = entitiesToActuallyDestroy.stream().mapToInt(uuid -> realToFakeId.getOrDefault(uuid, 0)).filter(id -> id != 0).toArray();
            if (idsToDestroy.length > 0) {
                fakeEntityFinalPackets.add(new EntitiesDestroyS2CPacket(idsToDestroy));
            }
            entitiesToActuallyDestroy.forEach(uuid -> fakeEntityFlickerGuard.put(uuid, FLICKER_GUARD_TICKS));
        }

        shownFakeEntities.clear();
        shownFakeEntities.addAll(entitiesToActuallyShow);
        this.entitiesToUpdateOnMainThread = new HashMap<>(visibleRealEntities);

        Set<UUID> allInRangeUuids = nearbyEntities.stream().map(Entity::getUuid).collect(Collectors.toSet());
        hiddenEntities.removeIf(uuid -> !allInRangeUuids.contains(uuid));
        flickerGuard.keySet().removeIf(uuid -> !allInRangeUuids.contains(uuid));

        cursednessServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;
            blockUpdatesToSend.sendTo(player);
            packetList.forEach(p -> player.networkHandler.sendPacket(p));
            entitiesToHide.forEach(e -> player.networkHandler.sendPacket(Util.createEntityHidePacket(e.getId())));
            for (Entity entity : entitiesToShow) {
                new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {}).sendPackets(player, player.networkHandler::sendPacket);
            }
            fakeEntityFinalPackets.forEach(p -> player.networkHandler.sendPacket(p));

            List<Packet<?>> debugPackets = new ArrayList<>();
            boolean isDebugNow = player.getWorld().getGameRules().getBoolean(ImmersiveCursedness.PORTAL_DEBUG);

            if (!isDebugNow) {
                if (!debugEntityIds.isEmpty()) {
                    int[] idsToDestroy = debugEntityIds.stream().mapToInt(i -> i).toArray();
                    debugPackets.add(new EntitiesDestroyS2CPacket(idsToDestroy));
                    debugEntityIds.clear();
                    debugEntityUuids.clear();
                }
            } else {
                if (debugEntityIds.size() > currentDebugPoints.size()) {
                    List<Integer> idsToDestroy = new ArrayList<>();
                    while (debugEntityIds.size() > currentDebugPoints.size()) {
                        int id = debugEntityIds.remove(debugEntityIds.size() - 1);
                        idsToDestroy.add(id);
                        debugEntityUuids.remove(id);
                    }
                    debugPackets.add(new EntitiesDestroyS2CPacket(idsToDestroy.stream().mapToInt(i -> i).toArray()));
                }

                while (debugEntityIds.size() < currentDebugPoints.size()) {
                    int fakeId = nextFakeEntityId--;
                    UUID fakeUuid = UUID.randomUUID();
                    debugEntityIds.add(fakeId);
                    debugEntityUuids.put(fakeId, fakeUuid);

                    ChickenEntity tempChicken = new ChickenEntity(EntityType.CHICKEN, player.getWorld());
                    tempChicken.setGlowing(true);

                    Vec3d pos = currentDebugPoints.get(debugEntityIds.size() - 1);

                    debugPackets.add(new EntitySpawnS2CPacket(fakeId, fakeUuid, pos.x, pos.y, pos.z, 0, 0, EntityType.CHICKEN, 0, Vec3d.ZERO, 0));

                    List<DataTracker.SerializedEntry<?>> trackedValues = tempChicken.getDataTracker().getChangedEntries();
                    if (trackedValues != null && !trackedValues.isEmpty()) {
                        debugPackets.add(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
                    }
                }

                for (int i = 0; i < debugEntityIds.size(); i++) {
                    int id = debugEntityIds.get(i);
                    Vec3d pos = currentDebugPoints.get(i);
                    PlayerPosition playerPos = new PlayerPosition(pos, Vec3d.ZERO, 0, 0);
                    debugPackets.add(new EntityPositionS2CPacket(id, playerPos, Collections.emptySet(), true));
                }
            }
            debugPackets.forEach(p -> player.networkHandler.sendPacket(p));
        });
    }

    public void onRemoved() {
        cursednessServer.addTask(() -> {
            purgeCache();
        });
    }

    public void purgeCache() {
        if (cursednessServer == null) return;
        BlockUpdateMap updatesToSend = new BlockUpdateMap();
        final AsyncWorldView viewForLambda = this.sourceView != null ? this.sourceView : new AsyncWorldView(player.getWorld());
        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);
        blockCache.purgeAll((pos, cachedState) -> {
            BlockState originalState = viewForLambda.getBlock(pos);
            if (originalState != cachedState) updatesToSend.put(pos, originalState);
        });
        List<Entity> entitiesToShow = new ArrayList<>();
        if (!hiddenEntities.isEmpty()) {
            ServerWorld sourceWorld = player.getWorld();
            for (UUID uuid : hiddenEntities) {
                Entity entity = sourceWorld.getEntity(uuid);
                if (entity != null) entitiesToShow.add(entity);
            }
            hiddenEntities.clear();
        }
        flickerGuard.clear();
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
        lastTickVehicleMap.clear();
        entitiesToUpdateOnMainThread.clear();
        cursednessServer.addTask(() -> {
            if (!debugEntityIds.isEmpty()) {
                int[] idsToDestroy = debugEntityIds.stream().mapToInt(i -> i).toArray();
                player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(idsToDestroy));
                debugEntityIds.clear();
                debugEntityUuids.clear();
            }

            if (!player.networkHandler.isConnectionOpen()) return;
            updatesToSend.sendTo(player);
            for (Entity entity : entitiesToShow) {
                new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {}).sendPackets(player, player.networkHandler::sendPacket);
            }
            if (!fakeIdsToDestroy.isEmpty()) {
                player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(fakeIdsToDestroy.stream().mapToInt(i -> i).toArray()));
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
        return world.getEntitiesByType(TypeFilter.instanceOf(Entity.class), player.getBoundingBox().expand(range), (entity) -> !entity.equals(this.player) && entity.isAlive());
    }
}
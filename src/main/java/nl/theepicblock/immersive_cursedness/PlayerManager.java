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
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.DisplayEntity;
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
import net.minecraft.util.math.*;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import nl.theepicblock.immersive_cursedness.mixin.EntitySetHeadYawS2CPacketAccessor;
import nl.theepicblock.immersive_cursedness.objects.*;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
    private final List<Integer> raycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> raycastDebugEntityUuids = new HashMap<>();
    private final List<Integer> cornerRaycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> cornerRaycastDebugEntityUuids = new HashMap<>();
    private final List<Integer> offsetCornerRaycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> offsetCornerRaycastDebugEntityUuids = new HashMap<>();

    // For fake entities
    private final Map<UUID, Integer> realToFakeId = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> fakeToRealId = new ConcurrentHashMap<>();
    private final Set<UUID> shownFakeEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> fakeEntityFlickerGuard = new ConcurrentHashMap<>();
    private int nextFakeEntityId = -1000000;
    private int nextRaycastDebugEntityId = -2000000;
    private int nextCornerRaycastDebugEntityId = -3000000;
    private int nextOffsetCornerRaycastDebugEntityId = -4000000;

    // For vehicle dismount grace period
    private final Map<UUID, UUID> lastTickVehicleMap = new ConcurrentHashMap<>();

    private static final int FLICKER_GUARD_TICKS = 5;

    private AsyncWorldView sourceView;
    private AsyncWorldView destinationView;
    private ServerWorld currentSourceWorld;

    // Data prepared on the main thread
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

    private void processPortalRendering(Portal portal, ServerWorld sourceWorld, Set<BlockPos> blocksInView, Set<BlockPos> atmosphereBlocksInView, BlockUpdateMap blockUpdatesToSend, List<Packet<?>> packetList, Set<UUID> entitiesInCullingZone, List<Entity> nearbyEntities, List<Vec3d[]> raycastDebugData) {
        TransformProfile transformProfile = portal.getTransformProfile();
        if (transformProfile == null) return;

        BlockPos.iterate(portal.getLowerLeft(), portal.getUpperRight()).forEach(portalBlockPos -> {
            if (sourceView.getBlock(portalBlockPos).isOf(Blocks.NETHER_PORTAL)) {
                BlockPos immutablePos = portalBlockPos.toImmutable();
                blocksInView.add(immutablePos);
                BlockState newState = Blocks.AIR.getDefaultState();
                blockCache.put(immutablePos, newState);
                blockUpdatesToSend.put(immutablePos, newState);
            }
        });

        if (isOccludedByOppositeFrame(portal, sourceView, raycastDebugData)) {
            return;
        }

        final double atmosphereRadius = Math.sqrt(icConfig.squaredAtmosphereRadius);
        final ViewFrustum viewFrustum = new ViewFrustum(player.getEyePos(), portal, atmosphereRadius);

        double distanceToPortalPlane = Math.abs(Util.get(player.getEyePos(), Util.rotate(portal.getAxis())) - Util.get(portal.getLowerLeft(), Util.rotate(portal.getAxis())));
        double proximityBuffer = Math.max(0, distanceToPortalPlane + 15); // Magic number I found that works for the last layer problem
        int iterationDepth = (int)Math.ceil(distanceToPortalPlane + atmosphereRadius + proximityBuffer);

        final BlockState atmosphereBlock = (sourceWorld.getRegistryKey() == World.OVERWORLD ? Blocks.NETHER_WART_BLOCK : Blocks.BLUE_CONCRETE).getDefaultState();
        final BlockState atmosphereBetweenBlock = (sourceWorld.getRegistryKey() == World.OVERWORLD ? Blocks.RED_STAINED_GLASS : Blocks.BLUE_STAINED_GLASS).getDefaultState();

        final int bottomOfWorld = sourceWorld.getBottomY();

        for (Entity entity : nearbyEntities) {
            if (viewFrustum.contains(entity.getPos())) {
                entitiesInCullingZone.add(entity.getUuid());
            }
        }

        Box iterationBox = viewFrustum.getIterationBox(iterationDepth);
        BlockPos.Mutable mutPos = new BlockPos.Mutable();

        for (int y = MathHelper.floor(iterationBox.minY); y < MathHelper.ceil(iterationBox.maxY); y++) {
            mutPos.setY(y);
            for (int x = MathHelper.floor(iterationBox.minX); x < MathHelper.ceil(iterationBox.maxX); x++) {
                mutPos.setX(x);
                for (int z = MathHelper.floor(iterationBox.minZ); z < MathHelper.ceil(iterationBox.maxZ); z++) {
                    mutPos.setZ(z);

                    if (!viewFrustum.contains(mutPos) || isFrameBlock(mutPos, portal, sourceView)) {
                        continue;
                    }

                    BlockPos immutablePos = mutPos.toImmutable();
                    double distSq = portal.toFlatStandingRectangle().getCenter().squaredDistanceTo(mutPos.toCenterPos());

                    if (distSq > icConfig.squaredAtmosphereRadiusMinusOne) {
                        blocksInView.add(immutablePos);
                        BlockState atmosphereState = (distSq > icConfig.squaredAtmosphereRadius) ? atmosphereBlock : atmosphereBetweenBlock;

                        if (y == bottomOfWorld) atmosphereState = atmosphereBlock;
                        if (y == bottomOfWorld + 1) atmosphereState = atmosphereBetweenBlock;

                        BlockState cachedState = blockCache.get(immutablePos);
                        if (!atmosphereState.equals(cachedState)) {
                            blockCache.put(immutablePos, atmosphereState);
                            blockUpdatesToSend.put(immutablePos, atmosphereState);
                        }
                    } else {
                        blocksInView.add(immutablePos);
                        BlockPos transformedPos = transformProfile.transform(immutablePos);
                        BlockState newState;
                        BlockEntity newBlockEntity = null;

                        boolean occlude = !portal.hasCorners() && Util.get(transformedPos, Util.rotate(transformProfile.getTargetAxis(portal.getAxis()))) == Util.get(transformProfile.getTargetPos(), Util.rotate(transformProfile.getTargetAxis(portal.getAxis())));

                        if (occlude) {
                            newState = Blocks.AIR.getDefaultState();
                        } else {
                            BlockState stateFromOtherDimension = destinationView.getBlock(transformedPos);
                            if (stateFromOtherDimension.isOf(Blocks.NETHER_PORTAL)) {
                                newState = Blocks.AIR.getDefaultState();
                            } else {
                                newState = transformProfile.rotateState(stateFromOtherDimension);
                                newBlockEntity = destinationView.getBlockEntity(transformedPos);
                            }
                        }

                        if (y == bottomOfWorld) newState = atmosphereBlock;
                        if (y == bottomOfWorld + 1) newState = atmosphereBetweenBlock;

                        BlockState cachedState = blockCache.get(immutablePos);
                        if (!newState.equals(cachedState)) {
                            blockCache.put(immutablePos, newState);
                            blockUpdatesToSend.put(immutablePos, newState);
                            if (newBlockEntity != null) {
                                Packet<?> packet = Util.createFakeBlockEntityPacket(newBlockEntity, immutablePos, sourceWorld);
                                if (packet != null) {
                                    packetList.add(packet);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isOccludedByOppositeFrame(Portal portal, AsyncWorldView worldView, List<Vec3d[]> raycastDebugData) {
        final boolean debugEnabled = player.getWorld().getGameRules().getBoolean(ImmersiveCursedness.PORTAL_DEBUG);
        if (debugEnabled) {
            final Vec3d playerEyePos = player.getEyePos();
            final Vec3d[] tangentPoints = getTangentPoints(portal, playerEyePos);
            Vec3d closestTangentPoint = null;
            double minDistanceSq = Double.MAX_VALUE;

            for (Vec3d tangentPoint : tangentPoints) {
                double distSq = playerEyePos.squaredDistanceTo(tangentPoint);
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq;
                    closestTangentPoint = tangentPoint;
                }
            }
            if (closestTangentPoint != null) {
                Vec3d direction = closestTangentPoint.subtract(playerEyePos).normalize();
                Vec3d extendedEndPoint = closestTangentPoint.add(direction.multiply(10.0));
                raycastDebugData.add(new Vec3d[]{playerEyePos, extendedEndPoint});
            }
        }
        return false;
    }

    private boolean isFrameBlock(BlockPos pos, Portal portal, AsyncWorldView worldView) {
        if (!worldView.getBlock(pos).isFullCube(worldView, pos)) {
            return false;
        }

        Direction.Axis portalContentAxis = portal.getAxis();
        Direction.Axis portalPlaneAxis = Util.rotate(portalContentAxis);

        int portalPlaneCoordinate = Util.get(portal.getLowerLeft(), portalPlaneAxis);
        if (Util.get(pos, portalPlaneAxis) != portalPlaneCoordinate) {
            return false;
        }

        int topY = portal.getTop() + 1;
        int bottomY = portal.getBottom() - 1;
        int left = portal.getLeft() - 1;
        int right = portal.getRight() + 1;

        int y = pos.getY();
        int axisCoord = Util.get(pos, portalContentAxis);

        if ((y == topY || y == bottomY) && (axisCoord >= left && axisCoord <= right)) {
            return true;
        }

        return (axisCoord == left || axisCoord == right) && (y >= bottomY && y <= topY);
    }

    private Vec3d[] getTangentPoints(Portal portal, Vec3d playerEyePos) {
        Direction.Axis contentAxis = portal.getAxis();
        Direction.Axis planeAxis = Util.rotate(contentAxis);
        final double epsilon = 1.0E-5;

        double portalCenterOnPlaneAxis = Util.get(portal.getLowerLeft(), planeAxis) + 0.5;
        double playerPosOnPlaneAxis = Util.get(playerEyePos, planeAxis);

        double targetPlaneCoord;
        if (playerPosOnPlaneAxis > portalCenterOnPlaneAxis) {
            targetPlaneCoord = portalCenterOnPlaneAxis + 0.5;
        } else {
            targetPlaneCoord = portalCenterOnPlaneAxis - 0.5;
        }

        double topY = portal.getTop() + 1.0;
        double bottomY = portal.getBottom();
        double left = portal.getLeft();
        double right = portal.getRight() + 1.0;

        double midContentAxis = (left + right) / 2.0;
        double midY = (bottomY + topY) / 2.0;

        Vec3d[] points = new Vec3d[4];
        if (planeAxis == Direction.Axis.X) {
            points[0] = new Vec3d(targetPlaneCoord, topY - epsilon, midContentAxis);      // Top
            points[1] = new Vec3d(targetPlaneCoord, bottomY + epsilon, midContentAxis);   // Bottom
            points[2] = new Vec3d(targetPlaneCoord, midY, left + epsilon);               // Left
            points[3] = new Vec3d(targetPlaneCoord, midY, right - epsilon);              // Right
        } else {
            points[0] = new Vec3d(midContentAxis, topY - epsilon, targetPlaneCoord);      // Top
            points[1] = new Vec3d(midContentAxis, bottomY + epsilon, targetPlaneCoord);   // Bottom
            points[2] = new Vec3d(left + epsilon, midY, targetPlaneCoord);               // Left
            points[3] = new Vec3d(right - epsilon, midY, targetPlaneCoord);              // Right
        }
        return points;
    }


    public void tickAsync(int tickCount) {
        if (!((PlayerInterface) player).immersivecursedness$getEnabled() || player.isSleeping()) {
            if (isDebugCleanupNeeded()) {
                cursednessServer.addTask(this::purgeDebugEntities);
            }
            return;
        }

        final List<Entity> nearbyEntities = this.nearbyEntities;
        final Map<UUID, Entity> destinationEntityMap = this.destinationEntityMap;
        final List<Portal> portalsToProcess = this.portalsToProcess;

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

        final List<Packet<?>> packetsToSend = new ArrayList<>();
        final BlockUpdateMap blockUpdatesToSend = new BlockUpdateMap();
        final Set<BlockPos> blocksInViewPositions = new HashSet<>();
        final Set<BlockPos> atmosphereBlocksInView = new HashSet<>();
        final Set<UUID> entitiesInCullingZone = new HashSet<>();
        boolean isNearPortal = false;

        final List<Vec3d[]> currentRaycastDebugData = new ArrayList<>();
        final List<Vec3d[]> cornerRaycastDebugData = new ArrayList<>();
        final List<Vec3d[]> offsetCornerRaycastDebugData = new ArrayList<>();
        final boolean debugEnabled = player.getWorld().getGameRules().getBoolean(ImmersiveCursedness.PORTAL_DEBUG);

        for (Portal portal : portalsToProcess) {
            if (portal.isCloserThan(player.getPos(), 8)) {
                isNearPortal = true;
            }
            processPortalRendering(portal, sourceWorld, blocksInViewPositions, atmosphereBlocksInView, blockUpdatesToSend, packetsToSend, entitiesInCullingZone, nearbyEntities, currentRaycastDebugData);

            if (debugEnabled) {
                final double extensionLength = icConfig.portalDepth;
                java.util.function.Function<Vec3d, Vec3d> extendRay = (corner) -> {
                    Vec3d direction = corner.subtract(player.getEyePos());
                    if (direction.lengthSquared() < 1e-7) return corner;
                    return player.getEyePos().add(direction.normalize().multiply(extensionLength));
                };

                final Direction.Axis portalPlaneAxis_debug = Util.rotate(portal.getAxis());
                final double playerPlanePos_debug = Util.get(player.getEyePos(), portalPlaneAxis_debug);
                final double portalBlockCoordinate_debug = Util.get(portal.getLowerLeft(), portalPlaneAxis_debug);
                final double frontPlaneCoordinate_debug = (playerPlanePos_debug > portalBlockCoordinate_debug + 0.5)
                        ? portalBlockCoordinate_debug + 1.0
                        : portalBlockCoordinate_debug;

                FlatStandingRectangle originalAperture = new FlatStandingRectangle(
                        portal.getTop() + 1.0, portal.getBottom(),
                        portal.getLeft(), portal.getRight() + 1.0,
                        frontPlaneCoordinate_debug, portalPlaneAxis_debug
                );
                Vec3d[] originalCorners = {
                        originalAperture.getTopLeft(), originalAperture.getTopRight(),
                        originalAperture.getBottomLeft(), originalAperture.getBottomRight()
                };
                cornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(originalCorners[0])});
                cornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(originalCorners[1])});
                cornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(originalCorners[2])});
                cornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(originalCorners[3])});

                final double FRUSTUM_CORNER_OFFSET = 0.5;
                FlatStandingRectangle offsetAperture = new FlatStandingRectangle(
                        portal.getTop() + 1.0 + FRUSTUM_CORNER_OFFSET,
                        portal.getBottom() - FRUSTUM_CORNER_OFFSET,
                        portal.getLeft() - FRUSTUM_CORNER_OFFSET,
                        portal.getRight() + 1.0 + FRUSTUM_CORNER_OFFSET,
                        frontPlaneCoordinate_debug,
                        portalPlaneAxis_debug
                );
                Vec3d[] offsetCorners = {
                        offsetAperture.getTopLeft(), offsetAperture.getTopRight(),
                        offsetAperture.getBottomLeft(), offsetAperture.getBottomRight()
                };
                offsetCornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(offsetCorners[0])});
                offsetCornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(offsetCorners[1])});
                offsetCornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(offsetCorners[2])});
                offsetCornerRaycastDebugData.add(new Vec3d[]{player.getEyePos(), extendRay.apply(offsetCorners[3])});
            }
        }

        blockCache.purge(blocksInViewPositions, (pos, cachedState) -> {
            if (sourceView.getBlock(pos).isOf(Blocks.NETHER_PORTAL) && !portalsToProcess.isEmpty()) {
                return;
            }

            BlockState originalState = sourceView.getBlock(pos);
            if (!originalState.equals(cachedState)) {
                blockUpdatesToSend.put(pos, originalState);
                BlockEntity originalBlockEntity = sourceView.getBlockEntity(pos);
                if (originalBlockEntity != null) {
                    Packet<?> packet = Util.createFakeBlockEntityPacket(originalBlockEntity, pos, sourceWorld);
                    if (packet != null) {
                        packetsToSend.add(packet);
                    }
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
        entitiesToHide.forEach(e -> packetsToSend.add(Util.createEntityHidePacket(e.getId())));
        for (Entity entity : entitiesToShow) {
            new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {}).sendPackets(player, packetsToSend::add);
        }

        processFakeEntities(packetsToSend, destinationEntityMap, portalsToProcess);

        if (debugEnabled) {
            updateDebugEntities(packetsToSend, currentRaycastDebugData, cornerRaycastDebugData, offsetCornerRaycastDebugData);
        } else if (isDebugCleanupNeeded()) {
            purgeDebugEntities(packetsToSend);
        }

        cursednessServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;
            blockUpdatesToSend.sendTo(player);
            packetsToSend.forEach(p -> player.networkHandler.sendPacket(p));
        });
    }

    private void processFakeEntities(List<Packet<?>> packetsToSend, Map<UUID, Entity> destinationEntityMap, List<Portal> portalsToProcess) {
        Map<UUID, Entity> visibleRealEntities = new HashMap<>();
        Map<UUID, Portal> entityPortalContext = new HashMap<>();
        for (Entity realEntity : destinationEntityMap.values()) {
            for (Portal portal : portalsToProcess) {
                TransformProfile transformProfile = portal.getTransformProfile();
                if (transformProfile == null) continue;
                ViewFrustum viewFrustum = new ViewFrustum(player.getEyePos(), portal);
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
                packetsToSend.add(fakeEntitySpawnPackets.get(uuid));
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
            packetsToSend.add(new EntityPositionS2CPacket(fakeId, new PlayerPosition(fakePos, fakeVel, fakeYaw, realEntity.getPitch()), Collections.emptySet(), realEntity.isOnGround()));
            float fakeHeadYaw = transformProfile.untransformYaw(realEntity.getHeadYaw());
            byte headYawByte = (byte) MathHelper.floor(fakeHeadYaw * 256.0F / 360.0F);
            EntitySetHeadYawS2CPacket headYawPacket = new EntitySetHeadYawS2CPacket(realEntity, (byte)0);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setEntityId(fakeId);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setHeadYaw(headYawByte);
            packetsToSend.add(headYawPacket);

            if (isNew && realEntity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) realEntity;
                List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    equipmentList.add(Pair.of(slot, livingEntity.getEquippedStack(slot)));
                }
                if (!equipmentList.isEmpty()) {
                    packetsToSend.add(new EntityEquipmentUpdateS2CPacket(fakeId, equipmentList));
                }
            }
            if (isNew) {
                List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getChangedEntries();
                if (trackedValues != null && !trackedValues.isEmpty()) {
                    packetsToSend.add(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
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
                    packetsToSend.add(EntityPassengersSetS2CPacket.CODEC.decode(buf));
                }
            }
        }

        if (!entitiesToActuallyDestroy.isEmpty()) {
            int[] idsToDestroy = entitiesToActuallyDestroy.stream().mapToInt(uuid -> realToFakeId.getOrDefault(uuid, 0)).filter(id -> id != 0).toArray();
            if (idsToDestroy.length > 0) {
                packetsToSend.add(new EntitiesDestroyS2CPacket(idsToDestroy));
            }
            entitiesToActuallyDestroy.forEach(uuid -> fakeEntityFlickerGuard.put(uuid, FLICKER_GUARD_TICKS));
        }

        shownFakeEntities.clear();
        shownFakeEntities.addAll(entitiesToActuallyShow);
        this.entitiesToUpdateOnMainThread = new HashMap<>(visibleRealEntities);
    }

    private boolean isDebugCleanupNeeded() {
        return !raycastDebugEntityIds.isEmpty() || !cornerRaycastDebugEntityIds.isEmpty() || !offsetCornerRaycastDebugEntityIds.isEmpty();
    }

    private void purgeDebugEntities() {
        if (!player.networkHandler.isConnectionOpen()) return;
        purgeDebugEntities(player.networkHandler::sendPacket);
    }

    private void purgeDebugEntities(List<Packet<?>> packetList) {
        purgeDebugEntities(packetList::add);
    }

    private void purgeDebugEntities(java.util.function.Consumer<Packet<?>> packetConsumer) {
        if (!raycastDebugEntityIds.isEmpty()) {
            packetConsumer.accept(new EntitiesDestroyS2CPacket(raycastDebugEntityIds.stream().mapToInt(i -> i).toArray()));
            raycastDebugEntityIds.clear();
            raycastDebugEntityUuids.clear();
        }
        if (!cornerRaycastDebugEntityIds.isEmpty()) {
            packetConsumer.accept(new EntitiesDestroyS2CPacket(cornerRaycastDebugEntityIds.stream().mapToInt(i->i).toArray()));
            cornerRaycastDebugEntityIds.clear();
            cornerRaycastDebugEntityUuids.clear();
        }
        if (!offsetCornerRaycastDebugEntityIds.isEmpty()) {
            packetConsumer.accept(new EntitiesDestroyS2CPacket(offsetCornerRaycastDebugEntityIds.stream().mapToInt(i->i).toArray()));
            offsetCornerRaycastDebugEntityIds.clear();
            offsetCornerRaycastDebugEntityUuids.clear();
        }
    }

    private void updateDebugEntities(List<Packet<?>> packets, List<Vec3d[]> raycastData, List<Vec3d[]> cornerRaycastData, List<Vec3d[]> offsetCornerRaycastData) {
        updateDebugRaycastSet(packets, raycastData, raycastDebugEntityIds, raycastDebugEntityUuids, () -> nextRaycastDebugEntityId--, Blocks.RED_CONCRETE.getDefaultState());
        updateDebugRaycastSet(packets, cornerRaycastData, cornerRaycastDebugEntityIds, cornerRaycastDebugEntityUuids, () -> nextCornerRaycastDebugEntityId--, Blocks.YELLOW_CONCRETE.getDefaultState());
        updateDebugRaycastSet(packets, offsetCornerRaycastData, offsetCornerRaycastDebugEntityIds, offsetCornerRaycastDebugEntityUuids, () -> nextOffsetCornerRaycastDebugEntityId--, Blocks.GREEN_CONCRETE.getDefaultState());
    }

    private void updateDebugRaycastSet(List<Packet<?>> packets, List<Vec3d[]> raycastData, List<Integer> entityIds, Map<Integer, UUID> entityUuids, java.util.function.Supplier<Integer> idSupplier, BlockState blockState) {
        if (entityIds.size() > raycastData.size()) {
            List<Integer> idsToDestroy = new ArrayList<>();
            while (entityIds.size() > raycastData.size()) {
                int id = entityIds.remove(entityIds.size() - 1);
                idsToDestroy.add(id);
                entityUuids.remove(id);
            }
            packets.add(new EntitiesDestroyS2CPacket(idsToDestroy.stream().mapToInt(i -> i).toArray()));
        }
        while (entityIds.size() < raycastData.size()) {
            int fakeId = idSupplier.get();
            UUID fakeUuid = UUID.randomUUID();
            entityIds.add(fakeId);
            entityUuids.put(fakeId, fakeUuid);
            Vec3d start = raycastData.get(entityIds.size() - 1)[0];
            packets.add(new EntitySpawnS2CPacket(fakeId, fakeUuid, start.x, start.y, start.z, 0, 0, EntityType.BLOCK_DISPLAY, 0, Vec3d.ZERO, 0));
        }
        for (int i = 0; i < entityIds.size(); i++) {
            int id = entityIds.get(i);
            Vec3d[] ray = raycastData.get(i);
            Vec3d start = ray[0];
            Vec3d end = ray[1];

            packets.add(new EntityPositionS2CPacket(id, new PlayerPosition(start, Vec3d.ZERO, 0, 0), Collections.emptySet(), true));

            DisplayEntity.BlockDisplayEntity tempDisplay = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, player.getWorld());
            tempDisplay.setBlockState(blockState);
            tempDisplay.setDisplayWidth(1.0f);
            tempDisplay.setDisplayHeight(1.0f);
            tempDisplay.setViewRange(icConfig.renderDistance * 16.0f + icConfig.portalDepth);

            Vec3d dir = end.subtract(start);
            float length = (float) dir.length();
            if (length > 1e-5f) {
                dir = dir.normalize();
            }

            Vector3f translation = new Vector3f(0.0f, 0.0f, 0.0f);
            Quaternionf leftRotation = new Quaternionf().rotationTo(new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f((float)dir.x, (float)dir.y, (float)dir.z));
            Vector3f scale = new Vector3f(0.05f, 0.05f, length);
            Quaternionf rightRotation = new Quaternionf();
            AffineTransformation transform = new AffineTransformation(translation, leftRotation, scale, rightRotation);

            tempDisplay.setTransformation(transform);
            tempDisplay.setInterpolationDuration(0);
            tempDisplay.setStartInterpolation(0);

            List<DataTracker.SerializedEntry<?>> trackedValues = tempDisplay.getDataTracker().getChangedEntries();
            if (trackedValues != null && !trackedValues.isEmpty()) {
                packets.add(new EntityTrackerUpdateS2CPacket(id, trackedValues));
            }
        }
    }

    public void onRemoved() {
        cursednessServer.addTask(() -> {
            purgeCache();
            purgeDebugEntities();
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
            if (!player.networkHandler.isConnectionOpen()) return;
            purgeDebugEntities(player.networkHandler::sendPacket);
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
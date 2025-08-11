package com.lukarbonite.immersive_serverside.rendering;

import com.lukarbonite.immersive_serverside.IC_Config;
import com.lukarbonite.immersive_serverside.ImmersiveServerside;
import com.lukarbonite.immersive_serverside.Util;
import com.lukarbonite.immersive_serverside.objects.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles the rendering of blocks and culling of entities for a single portal view.
 */
public class PortalRenderer {
    private final ServerPlayerEntity player;
    private final IC_Config icConfig;
    private final BlockCache blockCache;
    private final Map<BlockPos, ViewFrustum> viewFrustumCache;
    private static final double TANGENT_INSET = 0.1;

    private enum TangentSide { TOP, BOTTOM, LEFT, RIGHT }

    public PortalRenderer(ServerPlayerEntity player, IC_Config icConfig, BlockCache blockCache, Map<BlockPos, ViewFrustum> viewFrustumCache) {
        this.player = player;
        this.icConfig = icConfig;
        this.blockCache = blockCache;
        this.viewFrustumCache = viewFrustumCache;
    }

    public void processPortal(Portal portal, AsyncWorldView sourceView, AsyncWorldView destinationView, Set<BlockPos> blocksInView, Map<BlockPos, TransformProfile> blockToProfileMap, BlockUpdateMap blockUpdatesToSend, List<Packet<?>> packetList, Set<UUID> entitiesInCullingZone, List<Entity> nearbyEntities, List<Vec3d[]> raycastDebugData) {
        TransformProfile transformProfile = portal.getTransformProfile();
        if (transformProfile == null) return;

        // Always clear out the portal blocks themselves to allow passthrough
        BlockPos.iterate(portal.getLowerLeft(), portal.getUpperRight()).forEach(portalBlockPos -> {
            if (sourceView.getBlock(portalBlockPos).isOf(Blocks.NETHER_PORTAL)) {
                BlockPos immutablePos = portalBlockPos.toImmutable();
                blocksInView.add(immutablePos);
                BlockState newState = Blocks.AIR.getDefaultState();
                blockCache.put(immutablePos, newState);
                blockUpdatesToSend.put(immutablePos, newState);
            }
        });

        // If occluded, skip the expensive rendering of the other side.
        if (isOccludedByOppositeFrame(portal, sourceView, raycastDebugData)) {
            return;
        }

        final double atmosphereRadius = Math.sqrt(icConfig.squaredAtmosphereRadius);
        final ViewFrustum viewFrustum = viewFrustumCache.computeIfAbsent(
                portal.getLowerLeft(),
                k -> new ViewFrustum(player.getEyePos(), portal, atmosphereRadius)
        );

        final FlatStandingRectangle portalRect = portal.toFlatStandingRectangle();
        final Vec3d portalCenter = portalRect.getCenter();
        final double squaredAtmosphereRadius = icConfig.squaredAtmosphereRadius;
        final double squaredAtmosphereRadiusMinusOne = icConfig.squaredAtmosphereRadiusMinusOne;

        double distanceToPortalPlane = Math.abs(Util.get(player.getEyePos(), Util.rotate(portal.getAxis())) - Util.get(portal.getLowerLeft(), Util.rotate(portal.getAxis())));
        double proximityBuffer = Math.max(0, distanceToPortalPlane + 15);
        int iterationDepth = (int)Math.ceil(distanceToPortalPlane + atmosphereRadius + proximityBuffer);

        ServerWorld sourceWorld = sourceView.getWorld();
        final BlockState atmosphereBlock = (sourceWorld.getRegistryKey() == World.OVERWORLD ? Blocks.NETHER_WART_BLOCK : Blocks.BLUE_CONCRETE).getDefaultState();
        final BlockState atmosphereBetweenBlock = (sourceWorld.getRegistryKey() == World.OVERWORLD ? Blocks.RED_STAINED_GLASS : Blocks.BLUE_STAINED_GLASS).getDefaultState();

        final int bottomOfWorld = sourceWorld.getBottomY();
        final int topOfWorld = sourceWorld.getTopYInclusive();

        for (Entity entity : nearbyEntities) {
            if (viewFrustum.contains(entity.getPos())) {
                entitiesInCullingZone.add(entity.getUuid());
            }
        }

        viewFrustum.iterate(posInFrustum -> {
            if (isFrameBlock(posInFrustum, portal, sourceView)) {
                return;
            }

            double distSq = portalCenter.squaredDistanceTo(posInFrustum.getX() + 0.5, posInFrustum.getY() + 0.5, posInFrustum.getZ() + 0.5);
            BlockPos immutablePos = posInFrustum.toImmutable();
            blocksInView.add(immutablePos);

            if (distSq > squaredAtmosphereRadiusMinusOne) {
                BlockState atmosphereState = (distSq > squaredAtmosphereRadius) ? atmosphereBlock : atmosphereBetweenBlock;
                if (posInFrustum.getY() == bottomOfWorld) atmosphereState = atmosphereBlock;
                if (posInFrustum.getY() == bottomOfWorld + 1) atmosphereState = atmosphereBetweenBlock;

                BlockState cachedState = blockCache.get(immutablePos);
                if (!atmosphereState.equals(cachedState)) {
                    blockCache.put(immutablePos, atmosphereState);
                    blockUpdatesToSend.put(immutablePos, atmosphereState);
                }
            } else {
                blockToProfileMap.put(immutablePos, transformProfile);
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

                if (posInFrustum.getY() == bottomOfWorld) newState = atmosphereBlock;
                if (posInFrustum.getY() == bottomOfWorld + 1) newState = atmosphereBetweenBlock;

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
        }, iterationDepth, bottomOfWorld, topOfWorld);
    }

    private boolean isOccludedByOppositeFrame(Portal portal, AsyncWorldView worldView, List<Vec3d[]> raycastDebugData) {
        final Vec3d playerEyePos = player.getEyePos();
        final Map<TangentSide, Vec3d> tangentPoints = getTangentPoints(portal, playerEyePos);

        if (tangentPoints.isEmpty()) {
            return false;
        }

        Vec3d shortestRayTangentPoint = null;
        TangentSide shortestRaySide = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (Map.Entry<TangentSide, Vec3d> entry : tangentPoints.entrySet()) {
            double distSq = playerEyePos.squaredDistanceTo(entry.getValue());
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                shortestRayTangentPoint = entry.getValue();
                shortestRaySide = entry.getKey();
            }
        }

        if (shortestRayTangentPoint == null) {
            return false;
        }

        final Vec3d start = playerEyePos;
        final Vec3d direction = shortestRayTangentPoint.subtract(start).normalize();
        final Vec3d end = start.add(direction.multiply(icConfig.portalDepth));

        if (player.getWorld().getGameRules().getBoolean(ImmersiveServerside.PORTAL_DEBUG)) {
            raycastDebugData.add(new Vec3d[]{start, end});
        }

        BlockHitResult hitResult = worldView.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));

        if (hitResult.getType() == HitResult.Type.MISS) {
            return false;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState hitState = worldView.getBlock(hitPos);

        return hitState.isOpaqueFullCube() && isBlockOnOppositeFrame(hitPos, portal, shortestRaySide);
    }

    private boolean isBlockOnOppositeFrame(BlockPos blockPos, Portal portal, TangentSide tangentSide) {
        Direction.Axis contentAxis = portal.getAxis();
        int y = blockPos.getY();
        int axisCoord = Util.get(blockPos, contentAxis);

        int topY = portal.getTop() + 1;
        int bottomY = portal.getBottom() - 1;
        int left = portal.getLeft() - 1;
        int right = portal.getRight() + 1;

        boolean isTopFrame = y == topY && (axisCoord >= left && axisCoord <= right);
        boolean isBottomFrame = y == bottomY && (axisCoord >= left && axisCoord <= right);
        boolean isLeftFrame = axisCoord == left && (y >= bottomY && y <= topY);
        boolean isRightFrame = axisCoord == right && (y >= bottomY && y <= topY);

        return switch (tangentSide) {
            case TOP -> isBottomFrame;
            case BOTTOM -> isTopFrame;
            case LEFT -> isRightFrame;
            case RIGHT -> isLeftFrame;
        };
    }

    private Map<TangentSide, Vec3d> getTangentPoints(Portal portal, Vec3d playerEyePos) {
        Direction.Axis contentAxis = portal.getAxis();
        Direction.Axis planeAxis = Util.rotate(contentAxis);

        double portalBlockPlaneCoord = Util.get(portal.getLowerLeft(), planeAxis);
        double playerPlaneCoord = Util.get(playerEyePos, planeAxis);
        double closePlaneCoordinate = (playerPlaneCoord > portalBlockPlaneCoord + 0.5) ? portalBlockPlaneCoord + 1.0 : portalBlockPlaneCoord;

        double topY = portal.getTop() + 1.0 - TANGENT_INSET;
        double bottomY = portal.getBottom() + TANGENT_INSET;
        double left = portal.getLeft() + TANGENT_INSET;
        double right = portal.getRight() + 1.0 - TANGENT_INSET;

        double midContentAxis = (portal.getLeft() + portal.getRight() + 1.0) / 2.0;
        double midY = (portal.getBottom() + portal.getTop() + 1.0) / 2.0;

        Map<TangentSide, Vec3d> points = new EnumMap<>(TangentSide.class);

        if (planeAxis == Direction.Axis.X) {
            points.put(TangentSide.TOP, new Vec3d(closePlaneCoordinate, topY, midContentAxis));
            points.put(TangentSide.BOTTOM, new Vec3d(closePlaneCoordinate, bottomY, midContentAxis));
            points.put(TangentSide.LEFT, new Vec3d(closePlaneCoordinate, midY, left));
            points.put(TangentSide.RIGHT, new Vec3d(closePlaneCoordinate, midY, right));
        } else { // planeAxis == Z
            points.put(TangentSide.TOP, new Vec3d(midContentAxis, topY, closePlaneCoordinate));
            points.put(TangentSide.BOTTOM, new Vec3d(midContentAxis, bottomY, closePlaneCoordinate));
            points.put(TangentSide.LEFT, new Vec3d(left, midY, closePlaneCoordinate));
            points.put(TangentSide.RIGHT, new Vec3d(right, midY, closePlaneCoordinate));
        }
        return points;
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
}
package nl.theepicblock.immersive_cursedness;

import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import nl.theepicblock.immersive_cursedness.objects.AsyncWorldView;
import nl.theepicblock.immersive_cursedness.objects.DummyEntity;
import nl.theepicblock.immersive_cursedness.objects.Portal;
import nl.theepicblock.immersive_cursedness.objects.TransformProfile;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PortalManager {
    private final ServerPlayerEntity player;
    private final IC_Config icconfig;
    private final Map<BlockPos, Portal> portals = new HashMap<>();
    private final Map<BlockPos, Integer> portalGracePeriods = new HashMap<>();
    private final Map<BlockPos, TransformProfile> transformProfileCache = new HashMap<>();
    private static final int GRACE_PERIOD_TICKS = 4;

    public PortalManager(ServerPlayerEntity player, IC_Config icconfig) {
        this.player = player;
        this.icconfig = icconfig;
    }

    public void update(AsyncWorldView worldView) {
        ServerWorld world = player.getWorld();
        ServerWorld destination = Util.getDestination(world);

        portalGracePeriods.replaceAll((k, v) -> v - 1);
        portalGracePeriods.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue() <= 0;
            if (expired) {
                // When a portal expires, we should also release the chunk ticket
                TransformProfile profile = transformProfileCache.get(entry.getKey());
                if (profile != null) {
                    BlockPos targetPos = profile.getTargetPos();
                    // CORRECTED: removeTicket only takes 3 arguments
                    destination.getChunkManager().removeTicket(ChunkTicketType.PORTAL, new ChunkPos(targetPos), 3);
                }
                portals.remove(entry.getKey());
                transformProfileCache.remove(entry.getKey());
            }
            return expired;
        });

        List<PointOfInterest> allPortalPois = getPortalsInChunkRadius(world.getPointOfInterestStorage(), player.getBlockPos(), icconfig.renderDistance)
                .collect(Collectors.toList());
        Set<BlockPos> poiPositions = allPortalPois.stream().map(PointOfInterest::getPos).collect(Collectors.toSet());
        Set<BlockPos> checkedPortalBlocks = new HashSet<>();

        for (PointOfInterest portalPoi : allPortalPois) {
            BlockPos startPos = portalPoi.getPos();
            if (checkedPortalBlocks.contains(startPos)) {
                continue;
            }

            List<BlockPos> currentPortalBlocks = new ArrayList<>();
            Queue<BlockPos> searchQueue = new ArrayDeque<>();
            searchQueue.add(startPos);
            checkedPortalBlocks.add(startPos);

            while (!searchQueue.isEmpty()) {
                BlockPos currentPos = searchQueue.poll();
                currentPortalBlocks.add(currentPos);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos neighbor = currentPos.add(dx, dy, dz);
                            if (poiPositions.contains(neighbor) && checkedPortalBlocks.add(neighbor)) {
                                searchQueue.add(neighbor);
                            }
                        }
                    }
                }
            }

            if (currentPortalBlocks.isEmpty()) continue;

            BlockPos portalKey = Util.getCanonicalPos(currentPortalBlocks);
            portalGracePeriods.put(portalKey, GRACE_PERIOD_TICKS);

            // Get or create the transformation profile.
            TransformProfile transformProfile = transformProfileCache.computeIfAbsent(portalKey, k -> createTransformProfile(k, destination));

            if (transformProfile == null) {
                ImmersiveCursedness.LOGGER.warn("Could not create a valid teleport target for portal at " + portalKey);
                continue;
            }

            // The corrected chunk ticket logic:
            BlockPos targetPos = transformProfile.getTargetPos();
            // CORRECTED: addTicket only takes 3 arguments
            destination.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(targetPos), 3);

            if (!portals.containsKey(portalKey)) {
                BlockState startBlockState = worldView.getBlock(startPos);
                if (!startBlockState.contains(NetherPortalBlock.AXIS)) continue;

                Direction.Axis axis = startBlockState.get(NetherPortalBlock.AXIS);
                BlockBox bounds = Util.getBoundingBox(currentPortalBlocks);
                BlockPos upperRight = new BlockPos(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
                BlockPos lowerLeft = new BlockPos(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());

                boolean hasCorners = hasCorners(worldView, upperRight, lowerLeft, axis);
                portals.put(portalKey, new Portal(upperRight, lowerLeft, axis, hasCorners, transformProfile));
            }
        }
    }

    private boolean hasCorners(AsyncWorldView world, BlockPos upperRight, BlockPos lowerLeft, Direction.Axis axis) {
        int otherCoord = Util.get(upperRight, Util.rotate(axis));
        BlockPos.Mutable mutPos = new BlockPos.Mutable();
        Util.set(mutPos, otherCoord, Util.rotate(axis));

        int[] yCoords = {lowerLeft.getY() - 1, upperRight.getY() + 1};
        int[] axisCoords = {Util.get(lowerLeft, axis) - 1, Util.get(upperRight, axis) + 1};

        for (int y : yCoords) {
            mutPos.setY(y);
            for (int ac : axisCoords) {
                Util.set(mutPos, ac, axis);
                if (!isValidFrameBlock(world, mutPos)) return false;
            }
        }
        return true;
    }

    private boolean isValidFrameBlock(AsyncWorldView world, BlockPos pos) {
        BlockState state = world.getBlock(pos);
        // isFullCube is deprecated. A more reliable check is for a solid block with a full cube collision shape.
        return state.isSolid() && state.getCollisionShape(world, pos).equals(VoxelShapes.fullCube());
    }

    private TransformProfile createTransformProfile(BlockPos pos, ServerWorld destination) {
        DummyEntity dummyEntity = new DummyEntity(player.getWorld(), pos);
        TeleportTarget teleportTarget = dummyEntity.getTeleportTargetB(destination);

        if (teleportTarget == null) {
            return null;
        }

        BlockPos targetPos = BlockPos.ofFloored(teleportTarget.position());
        return new TransformProfile(pos, targetPos, 0, (int) teleportTarget.yaw());
    }

    public Collection<Portal> getPortals() {
        return portals.values();
    }

    private Stream<PointOfInterest> getPortalsInChunkRadius(PointOfInterestStorage storage, BlockPos pos, int radius) {
        return ChunkPos.stream(new ChunkPos(pos), radius)
                .flatMap(chunkPos -> storage.getInChunk(poi -> poi.matchesKey(PointOfInterestTypes.NETHER_PORTAL), chunkPos, PointOfInterestStorage.OccupationStatus.ANY));
    }
}
package nl.theepicblock.immersive_cursedness;

import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import nl.theepicblock.immersive_cursedness.objects.DummyEntity;
import nl.theepicblock.immersive_cursedness.objects.Portal;
import nl.theepicblock.immersive_cursedness.objects.TransformProfile;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PortalManager {
    private final Map<BlockPos, Portal> portals = new HashMap<>();
    private final Map<BlockPos, Integer> portalGracePeriods = new HashMap<>();
    private final ServerPlayerEntity player;
    private final IC_Config icconfig;
    public static boolean portalForcerMixinActivate = false;
    private final Map<BlockPos, TransformProfile> transformProfileCache = new HashMap<>();
    private static final int GRACE_PERIOD_TICKS = 100; // 5 seconds

    public PortalManager(ServerPlayerEntity player, IC_Config icconfig) {
        this.player = player;
        this.icconfig = icconfig;
    }

    public void update() {
        ServerWorld world = ((PlayerInterface)player).immersivecursedness$getUnfakedWorld();
        ServerWorld destination = Util.getDestination(player);
        Set<BlockPos> foundPortalKeys = new HashSet<>();

        // Get all portal POIs in range and their block positions.
        List<PointOfInterest> allPortalPois = getPortalsInChunkRadius(world.getPointOfInterestStorage(), player.getBlockPos(), icconfig.renderDistance).collect(Collectors.toList());
        Set<BlockPos> checked = new HashSet<>();

        for (PointOfInterest portalPoi : allPortalPois) {
            BlockPos startPos = portalPoi.getPos();
            if (checked.contains(startPos)) {
                continue;
            }

            // BFS to find all connected portal blocks for one portal structure
            List<BlockPos> currentPortalBlocks = new ArrayList<>();
            Queue<BlockPos> searchQueue = new ArrayDeque<>();
            searchQueue.add(startPos);
            checked.add(startPos);

            while (!searchQueue.isEmpty()) {
                BlockPos currentPos = searchQueue.poll();
                currentPortalBlocks.add(currentPos);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = currentPos.offset(dir);
                    // Use the POI list as a boundary for the search
                    if (allPortalPois.stream().anyMatch(p -> p.getPos().equals(neighbor)) && checked.add(neighbor)) {
                        searchQueue.add(neighbor);
                    }
                }
            }

            // Calculate canonical bounds to get a stable key
            if (currentPortalBlocks.isEmpty()) continue;

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (BlockPos pos : currentPortalBlocks) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }

            try {
                BlockPos lowerLeft = new BlockPos(minX, minY, minZ);
                foundPortalKeys.add(lowerLeft);

                // This portal is visible. Reset its grace period.
                portalGracePeriods.put(lowerLeft, GRACE_PERIOD_TICKS);

                // If we don't already know about this portal, create it.
                if (!portals.containsKey(lowerLeft)) {
                    BlockState startBlockState = world.getBlockState(startPos);
                    Direction.Axis axis = startBlockState.get(NetherPortalBlock.AXIS);
                    BlockPos upperRight = new BlockPos(maxX, maxY, maxZ);

                    TransformProfile transformProfile = transformProfileCache.computeIfAbsent(lowerLeft, ll -> createTransformProfile(ll, destination));
                    if (transformProfile == null) continue;

                    boolean hasCorners = hasCorners(world, upperRight, lowerLeft, axis);
                    portals.put(lowerLeft, new Portal(upperRight, lowerLeft, axis, hasCorners, transformProfile));
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // --- Grace Period Handling ---
        // Tick down grace periods for all portals, and remove any that have expired.
        double maxDistSq = Math.pow(icconfig.renderDistance * 16, 2);
        Iterator<Map.Entry<BlockPos, Integer>> graceIterator = portalGracePeriods.entrySet().iterator();
        while (graceIterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = graceIterator.next();
            BlockPos portalKey = entry.getKey();
            int newGrace = entry.getValue() - 30; // 30 ticks have passed since last update

            Portal portal = portals.get(portalKey);
            // Remove if grace period is over OR if it's too far away
            if (newGrace <= 0 || (portal != null && portal.getDistance(player.getBlockPos()) > maxDistSq)) {
                graceIterator.remove();
                portals.remove(portalKey);
                transformProfileCache.remove(portalKey);
            } else {
                entry.setValue(newGrace);
            }
        }
    }

    private static boolean hasCorners(ServerWorld world, BlockPos upperRight, BlockPos lowerLeft, Direction.Axis axis) {
        int frameLeft = Util.get(lowerLeft, axis)-1;
        int frameRight = Util.get(upperRight, axis)+1;
        int frameTop = upperRight.getY()+1;
        int frameBottom = lowerLeft.getY()-1;
        int oppositeAxis = Util.get(upperRight, Util.rotate(axis));

        BlockPos.Mutable mutPos = new BlockPos.Mutable();
        Util.set(mutPos, oppositeAxis, Util.rotate(axis));

        mutPos.setY(frameBottom);
        Util.set(mutPos, frameLeft, axis);
        if (!isValidCornerBlock(world,mutPos)) return false;
        Util.set(mutPos, frameRight, axis);
        if (!isValidCornerBlock(world,mutPos)) return false;

        mutPos.setY(frameTop);
        Util.set(mutPos, frameLeft, axis);
        if (!isValidCornerBlock(world,mutPos)) return false;
        Util.set(mutPos, frameRight, axis);
        if (!isValidCornerBlock(world,mutPos)) return false;

        return true;
    }

    private static boolean isValidCornerBlock(ServerWorld world, BlockPos pos) {
        return Util.getBlockAsync(world, pos).isFullCube(world, pos);
    }

    private TransformProfile createTransformProfile(BlockPos pos, ServerWorld destination) {
        DummyEntity dummyEntity = new DummyEntity(((PlayerInterface)player).immersivecursedness$getUnfakedWorld(), pos);
        dummyEntity.setBodyYaw(0);
        portalForcerMixinActivate = true;
        TeleportTarget teleportTarget = dummyEntity.getTeleportTargetB(destination);
        portalForcerMixinActivate = false;

        if (teleportTarget == null) {
            return null;
        }

        return new TransformProfile(
                pos,
                new BlockPos(MathHelper.floor(teleportTarget.position().x), MathHelper.floor(teleportTarget.position().y), MathHelper.floor(teleportTarget.position().z)),
                0,
                (int)teleportTarget.yaw());
    }

    public Collection<Portal> getPortals() {
        return portals.values();
    }

    private static Stream<PointOfInterest> getPortalsInChunkRadius(PointOfInterestStorage storage, BlockPos pos, int radius) {
        return ChunkPos.stream(new ChunkPos(pos), radius).flatMap((chunkPos) -> storage.getInChunk((poi) -> poi.matchesKey(PointOfInterestTypes.NETHER_PORTAL), chunkPos, PointOfInterestStorage.OccupationStatus.ANY));
    }
}
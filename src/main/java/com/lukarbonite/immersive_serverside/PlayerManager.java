package com.lukarbonite.immersive_serverside;

import com.lukarbonite.immersive_serverside.objects.*;
import com.lukarbonite.immersive_serverside.rendering.DebugVisualizer;
import com.lukarbonite.immersive_serverside.rendering.FakeEntityManager;
import com.lukarbonite.immersive_serverside.rendering.PortalLightingManager;
import com.lukarbonite.immersive_serverside.rendering.PortalRenderer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.ChunkNibbleArray;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {
    private final IC_Config icConfig;
    private final ServerPlayerEntity player;
    private final ServersideServer serversideServer;
    private final PortalManager portalManager;
    private final BlockCache blockCache = new BlockCache();
    private final Set<UUID> hiddenEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> flickerGuard = new ConcurrentHashMap<>();

    private final FakeEntityManager fakeEntityManager;
    private final DebugVisualizer debugVisualizer;

    private AsyncWorldView sourceView;
    private AsyncWorldView destinationView;
    private ServerWorld currentSourceWorld;

    private volatile List<Entity> nearbyEntities = new ArrayList<>();
    private volatile Map<UUID, Entity> destinationEntityMap = new HashMap<>();
    private volatile List<Portal> portalsToProcess = new ArrayList<>();
    private final Set<BlockPos> previouslyVisibleBlocks = ConcurrentHashMap.newKeySet();

    private final Map<BlockPos, ViewFrustum> viewFrustumCache = new HashMap<>();
    private final Map<BlockPos, ViewFrustum> entityFrustumCache = new HashMap<>();
    private Vec3d lastPlayerPosForFrustumCache = Vec3d.ZERO;
    private Vec2f lastPlayerLookForFrustumCache = Vec2f.ZERO;

    private static final int FLICKER_GUARD_TICKS = 5;

    public PlayerManager(ServerPlayerEntity player, IC_Config icConfig, ServersideServer serversideServer) {
        this.player = player;
        this.icConfig = icConfig;
        this.serversideServer = serversideServer;
        this.portalManager = new PortalManager(player, icConfig);
        this.fakeEntityManager = new FakeEntityManager(player);
        this.debugVisualizer = new DebugVisualizer(player, icConfig);
    }

    public void tickMainThread(int tickCount) {
        ServerWorld sourceWorld = player.getWorld();

        boolean worldChanged = sourceWorld != this.currentSourceWorld;
        if (worldChanged || this.sourceView == null || this.destinationView == null) {
            if (this.sourceView != null) {
                serversideServer.addTask(this::purgeAllVisuals);
            }
            this.currentSourceWorld = sourceWorld;
            this.sourceView = new AsyncWorldView(sourceWorld);
            this.destinationView = new AsyncWorldView(Util.getDestination(sourceWorld));
        }

        if (tickCount % 30 == 0 || worldChanged) {
            portalManager.update(sourceView);
        }

        this.portalsToProcess = new ArrayList<>(portalManager.getPortals());
        this.portalsToProcess.sort(Comparator.comparing(Portal::getLowerLeft));
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
                                boolean isRiddenByPlayer = entity.getPassengerList().stream().anyMatch(p -> p instanceof net.minecraft.entity.player.PlayerEntity);
                                return entity.shouldSave() || entity instanceof ServerPlayerEntity || isRiddenByPlayer;
                            })
                            .forEach(entity -> newDestinationEntities.putIfAbsent(entity.getUuid(), entity));
                }
            }
        }
        this.destinationEntityMap = newDestinationEntities;

        Map<UUID, Entity> entitiesToUpdate = fakeEntityManager.getEntitiesToUpdateOnMainThread();
        if (!entitiesToUpdate.isEmpty()) {
            for (Map.Entry<UUID, Entity> entry : entitiesToUpdate.entrySet()) {
                Entity realEntity = entry.getValue();
                Integer fakeId = fakeEntityManager.getFakeId(realEntity.getUuid());
                if (fakeId != null) {
                    List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getDirtyEntries();
                    if (trackedValues != null && !trackedValues.isEmpty()) {
                        player.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
                    }
                }
            }
        }
    }

    public void tickAsync(int tickCount) {
        if (!((PlayerInterface) player).immersivecursedness$getEnabled() || player.isSleeping()) {
            if (debugVisualizer.isCleanupNeeded()) {
                serversideServer.addTask(this::purgeDebugVisuals);
            }
            return;
        }

        final Vec3d currentPlayerPos = player.getPos();
        final Vec2f currentPlayerLook = player.getRotationClient();
        if (!currentPlayerPos.equals(this.lastPlayerPosForFrustumCache) || !currentPlayerLook.equals(this.lastPlayerLookForFrustumCache)) {
            this.viewFrustumCache.clear();
            this.entityFrustumCache.clear();
            this.lastPlayerPosForFrustumCache = currentPlayerPos;
            this.lastPlayerLookForFrustumCache = currentPlayerLook;
        }

        flickerGuard.replaceAll((k, v) -> v - 1);
        flickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);
        fakeEntityManager.tick();

        ServerWorld sourceWorld = this.currentSourceWorld;
        if (sourceWorld == null) return;
        if (player.hasPortalCooldown()) {
            ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);
            return;
        }

        final List<Packet<?>> packetsToSend = new ArrayList<>();
        final BlockUpdateMap blockUpdatesToSend = new BlockUpdateMap();
        final Set<BlockPos> blocksInViewPositions = new HashSet<>();
        final Map<BlockPos, TransformProfile> blockToProfileMap = new HashMap<>();
        final Set<UUID> entitiesInCullingZone = new HashSet<>();
        boolean isNearPortal = false;

        final boolean debugEnabled = player.getWorld().getGameRules().getBoolean(ImmersiveServerside.PORTAL_DEBUG);
        final List<Vec3d[]> raycastDebugData = new ArrayList<>();
        final List<Vec3d[]> cornerRaycastDebugData = new ArrayList<>();
        final List<Vec3d[]> offsetCornerRaycastDebugData = new ArrayList<>();

        PortalRenderer portalRenderer = new PortalRenderer(player, icConfig, blockCache, viewFrustumCache);
        for (Portal portal : portalsToProcess) {
            if (portal.isCloserThan(player.getPos(), 8)) {
                isNearPortal = true;
            }
            portalRenderer.processPortal(portal, sourceView, destinationView, blocksInViewPositions, blockToProfileMap, blockUpdatesToSend, packetsToSend, entitiesInCullingZone, nearbyEntities, raycastDebugData);
        }

        Set<BlockPos> blocksToPurge = new HashSet<>(this.previouslyVisibleBlocks);
        blocksToPurge.removeAll(blocksInViewPositions);

        final Map<ChunkSectionPos, Pair<ChunkNibbleArray, ChunkNibbleArray>> sectionLightData = new HashMap<>();
        packetsToSend.addAll(PortalLightingManager.calculateLighting(blockToProfileMap, sourceView, destinationView, sectionLightData));

        final Set<ChunkSectionPos> purgedSections = new HashSet<>();
        blockCache.purgePositions(blocksToPurge, (pos, cachedState) -> {
            if (sourceView.getBlock(pos).isOf(Blocks.NETHER_PORTAL) && !portalsToProcess.isEmpty()) return;
            purgedSections.add(ChunkSectionPos.from(pos));
            BlockState originalState = sourceView.getBlock(pos);
            if (!originalState.equals(cachedState)) {
                blockUpdatesToSend.put(pos, originalState);
                BlockEntity originalBlockEntity = sourceView.getBlockEntity(pos);
                if (originalBlockEntity != null) {
                    Packet<?> packet = Util.createFakeBlockEntityPacket(originalBlockEntity, pos, sourceWorld);
                    if (packet != null) packetsToSend.add(packet);
                }
            }
        });

        purgedSections.removeAll(sectionLightData.keySet());
        packetsToSend.addAll(PortalLightingManager.getRevertPackets(purgedSections, sourceWorld));

        this.previouslyVisibleBlocks.clear();
        this.previouslyVisibleBlocks.addAll(blocksInViewPositions);

        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(isNearPortal);

        processRealEntities(packetsToSend, entitiesInCullingZone, nearbyEntities);
        packetsToSend.addAll(fakeEntityManager.process(destinationEntityMap, portalsToProcess, entityFrustumCache));

        if (debugEnabled) {
            debugVisualizer.update(packetsToSend, raycastDebugData, cornerRaycastDebugData, offsetCornerRaycastDebugData);
        } else if (debugVisualizer.isCleanupNeeded()) {
            debugVisualizer.purge(packetsToSend);
        }

        serversideServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;
            blockUpdatesToSend.sendTo(player);
            packetsToSend.forEach(p -> player.networkHandler.sendPacket(p));
        });
    }

    private void processRealEntities(List<Packet<?>> packetsToSend, Set<UUID> entitiesInCullingZone, List<Entity> nearbyEntities) {
        List<Entity> entitiesToHide = new ArrayList<>();
        List<Entity> entitiesToShow = new ArrayList<>();
        for (Entity entity : nearbyEntities) {
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
        entitiesToHide.forEach(e -> packetsToSend.add(Util.createEntityHidePacket(e.getId())));
        for (Entity entity : entitiesToShow) {
            new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {}).sendPackets(player, packetsToSend::add);
        }
    }

    public void onRemoved() {
        serversideServer.addTask(this::purgeAllVisuals);
    }

    private void purgeAllVisuals() {
        if (!player.networkHandler.isConnectionOpen()) return;
        List<Packet<?>> packets = new ArrayList<>();
        packets.addAll(fakeEntityManager.getPurgePackets());
        debugVisualizer.purge(packets);

        fakeEntityManager.purgeAll();

        blockCache.purgeAll((pos, cachedState) -> {});
        previouslyVisibleBlocks.clear();
        viewFrustumCache.clear();
        entityFrustumCache.clear();

        packets.forEach(p -> player.networkHandler.sendPacket(p));
    }

    public void purgeCache() {
        if (serversideServer == null) return;

        BlockUpdateMap updatesToSend = new BlockUpdateMap();
        final AsyncWorldView viewForLambda = this.sourceView != null ? this.sourceView : new AsyncWorldView(player.getWorld());
        ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);

        this.previouslyVisibleBlocks.clear();
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

        List<Packet<?>> packets = new ArrayList<>(fakeEntityManager.getPurgePackets());
        fakeEntityManager.purgeAll();
        debugVisualizer.purge(packets);

        serversideServer.addTask(() -> {
            if (!player.networkHandler.isConnectionOpen()) return;
            updatesToSend.sendTo(player);
            packets.forEach(p -> player.networkHandler.sendPacket(p));

            for (Entity entity : entitiesToShow) {
                new EntityTrackerEntry(player.getWorld(), entity, 0, false, (p) -> {}, (p, l) -> {}).sendPackets(player, player.networkHandler::sendPacket);
            }
        });
    }

    private void purgeDebugVisuals() {
        if (!player.networkHandler.isConnectionOpen()) return;
        List<Packet<?>> packets = new ArrayList<>();
        debugVisualizer.purge(packets);
        packets.forEach(p -> player.networkHandler.sendPacket(p));
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
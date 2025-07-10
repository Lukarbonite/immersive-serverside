package nl.theepicblock.immersive_cursedness;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import nl.theepicblock.immersive_cursedness.objects.*;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlayerManager {
    private final IC_Config icConfig;
    private final ServerPlayerEntity player;
    private final CursednessServer cursednessServer;
    private final PortalManager portalManager;
    private final BlockCache blockCache = new BlockCache();
    private final List<UUID> hiddenEntities = new ArrayList<>();

    private AsyncWorldView sourceView;
    private AsyncWorldView destinationView;
    private ServerWorld currentSourceWorld;

    public PlayerManager(ServerPlayerEntity player, IC_Config icConfig, CursednessServer cursednessServer) {
        this.player = player;
        this.icConfig = icConfig;
        this.cursednessServer = cursednessServer;
        this.portalManager = new PortalManager(player, icConfig);
    }

    public void tick(int tickCount) {
        if (!((PlayerInterface) player).immersivecursedness$getEnabled() || player.isSleeping()) {
            return;
        }

        ServerWorld sourceWorld = player.getWorld();
        ServerWorld destinationWorld = Util.getDestination(sourceWorld);

        boolean worldChanged = sourceWorld != this.currentSourceWorld;
        if (worldChanged || this.sourceView == null || this.destinationView == null) {
            this.currentSourceWorld = sourceWorld;
            this.sourceView = new AsyncWorldView(sourceWorld, true);
            this.destinationView = new AsyncWorldView(destinationWorld, true);
            purgeCache();
        }

        if (tickCount % 30 == 0 || worldChanged) {
            portalManager.update(sourceView);
        }

        if (player.hasPortalCooldown()) {
            ((PlayerInterface) player).immersivecursedness$setCloseToPortal(false);
            return;
        }

        List<FlatStandingRectangle> viewRects = new ArrayList<>();
        Chunk2IntMap blocksInView = new Chunk2IntMap();
        BlockUpdateMap blockUpdatesToSend = new BlockUpdateMap();
        List<Packet<?>> packetList = new ArrayList<>();

        List<Entity> visibleEntities = getEntitiesInRange(sourceWorld);
        List<Entity> entitiesToHide = new ArrayList<>();
        List<Entity> entitiesToShow = new ArrayList<>();

        BlockState atmosphereBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_CONCRETE : Blocks.NETHER_WART_BLOCK).getDefaultState();
        BlockState atmosphereBetweenBlock = (sourceWorld.getRegistryKey() == World.NETHER ? Blocks.BLUE_STAINED_GLASS : Blocks.RED_STAINED_GLASS).getDefaultState();

        boolean isNearPortal = false;
        var bottomOfWorld = sourceWorld.getBottomY();

        for (Portal portal : portalManager.getPortals()) {
            if (portal.isCloserThan(player.getPos(), 8)) {
                isNearPortal = true;
            }

            TransformProfile transformProfile = portal.getTransformProfile();
            if (transformProfile == null) continue;

            if (tickCount % 40 == 0 || worldChanged) {
                BlockPos.iterate(portal.getLowerLeft(), portal.getUpperRight()).forEach(pos -> blockUpdatesToSend.put(pos.toImmutable(), Blocks.AIR.getDefaultState()));
            }

            FlatStandingRectangle baseRect = portal.toFlatStandingRectangle();
            Vec3d playerEyePos = player.getEyePos();

            for (int i = 1; i < icConfig.portalDepth; i++) {
                FlatStandingRectangle layerRect = baseRect.expand(i, playerEyePos);
                viewRects.add(layerRect);

                for (Entity entity : visibleEntities) {
                    if (layerRect.contains(entity.getPos())) {
                        if (!hiddenEntities.contains(entity.getUuid())) {
                            entitiesToHide.add(entity);
                        }
                    }
                }

                layerRect.iterateClamped(player.getPos(), icConfig.horizontalSendLimit, Util.calculateMinMax(sourceWorld, destinationWorld, transformProfile), (pos) -> {
                    double distSq = Util.getDistance(pos, portal.getLowerLeft());
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

        List<UUID> entitiesToHideUuids = entitiesToHide.stream().map(Entity::getUuid).collect(Collectors.toList());
        List<UUID> allVisibleEntitiesUUIDs = visibleEntities.stream().map(Entity::getUuid).collect(Collectors.toList());

        hiddenEntities.removeIf(uuid -> {
            boolean shouldBeVisible = allVisibleEntitiesUUIDs.contains(uuid) && !entitiesToHideUuids.contains(uuid);
            if (shouldBeVisible) {
                Entity entity = sourceWorld.getEntity(uuid);
                if (entity != null) entitiesToShow.add(entity);
            }
            return shouldBeVisible || !allVisibleEntitiesUUIDs.contains(uuid);
        });

        hiddenEntities.addAll(entitiesToHideUuids);

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
                // The correct constructor for a generic entity spawn packet uses this longer form.
                // entity.createSpawnPacket() cannot be used here as it requires an EntityTrackerEntry.
                player.networkHandler.sendPacket(
                        new EntitySpawnS2CPacket(
                                entity.getId(),
                                entity.getUuid(),
                                entity.getX(),
                                entity.getY(),
                                entity.getZ(),
                                entity.getPitch(),
                                entity.getYaw(),
                                entity.getType(),
                                0, // Entity data, often 0 for non-projectiles
                                entity.getVelocity(),
                                entity.getHeadYaw()
                        )
                );
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

        cursednessServer.addTask(() -> {
            if (player.networkHandler.isConnectionOpen()) {
                updatesToSend.sendTo(player);
            }
        });
    }

    @Nullable
    public TransformProfile getTransformProfileForBlock(BlockPos p) {
        for (Portal portal : portalManager.getPortals()) {
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
                (entity) -> !entity.isPlayer() && entity.isAlive()
        );
    }
}
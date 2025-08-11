package com.lukarbonite.immersive_serverside.rendering;

import com.lukarbonite.immersive_serverside.IC_Config;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.entity.data.DataTracker;


import java.util.*;
import java.util.function.Supplier;

/**
 * Manages the creation and destruction of debug entities for visualizing raycasts.
 */
public class DebugVisualizer {
    private final ServerPlayerEntity player;
    private final IC_Config icConfig;

    private final List<Integer> raycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> raycastDebugEntityUuids = new HashMap<>();
    private final List<Integer> cornerRaycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> cornerRaycastDebugEntityUuids = new HashMap<>();
    private final List<Integer> offsetCornerRaycastDebugEntityIds = new ArrayList<>();
    private final Map<Integer, UUID> offsetCornerRaycastDebugEntityUuids = new HashMap<>();

    private int nextRaycastDebugEntityId = -2000000;
    private int nextCornerRaycastDebugEntityId = -3000000;
    private int nextOffsetCornerRaycastDebugEntityId = -4000000;

    public DebugVisualizer(ServerPlayerEntity player, IC_Config icConfig) {
        this.player = player;
        this.icConfig = icConfig;
    }

    public boolean isCleanupNeeded() {
        return !raycastDebugEntityIds.isEmpty() || !cornerRaycastDebugEntityIds.isEmpty() || !offsetCornerRaycastDebugEntityIds.isEmpty();
    }

    public void purge(List<Packet<?>> packetList) {
        if (!raycastDebugEntityIds.isEmpty()) {
            packetList.add(new EntitiesDestroyS2CPacket(raycastDebugEntityIds.stream().mapToInt(i -> i).toArray()));
            raycastDebugEntityIds.clear();
            raycastDebugEntityUuids.clear();
        }
        if (!cornerRaycastDebugEntityIds.isEmpty()) {
            packetList.add(new EntitiesDestroyS2CPacket(cornerRaycastDebugEntityIds.stream().mapToInt(i->i).toArray()));
            cornerRaycastDebugEntityIds.clear();
            cornerRaycastDebugEntityUuids.clear();
        }
        if (!offsetCornerRaycastDebugEntityIds.isEmpty()) {
            packetList.add(new EntitiesDestroyS2CPacket(offsetCornerRaycastDebugEntityIds.stream().mapToInt(i->i).toArray()));
            offsetCornerRaycastDebugEntityIds.clear();
            offsetCornerRaycastDebugEntityUuids.clear();
        }
    }

    public void update(List<Packet<?>> packets, List<Vec3d[]> raycastData, List<Vec3d[]> cornerRaycastData, List<Vec3d[]> offsetCornerRaycastData) {
        updateDebugRaycastSet(packets, raycastData, raycastDebugEntityIds, raycastDebugEntityUuids, () -> nextRaycastDebugEntityId--, Blocks.RED_CONCRETE.getDefaultState());
        updateDebugRaycastSet(packets, cornerRaycastData, cornerRaycastDebugEntityIds, cornerRaycastDebugEntityUuids, () -> nextCornerRaycastDebugEntityId--, Blocks.YELLOW_CONCRETE.getDefaultState());
        updateDebugRaycastSet(packets, offsetCornerRaycastData, offsetCornerRaycastDebugEntityIds, offsetCornerRaycastDebugEntityUuids, () -> nextOffsetCornerRaycastDebugEntityId--, Blocks.GREEN_CONCRETE.getDefaultState());
    }

    private void updateDebugRaycastSet(List<Packet<?>> packets, List<Vec3d[]> raycastData, List<Integer> entityIds, Map<Integer, UUID> entityUuids, Supplier<Integer> idSupplier, BlockState blockState) {
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
}
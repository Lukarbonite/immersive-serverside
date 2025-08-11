package com.lukarbonite.immersive_serverside.rendering;

import com.lukarbonite.immersive_serverside.mixin.EntitySetHeadYawS2CPacketAccessor;
import com.lukarbonite.immersive_serverside.objects.Portal;
import com.lukarbonite.immersive_serverside.objects.TransformProfile;
import com.lukarbonite.immersive_serverside.objects.ViewFrustum;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of fake entities shown to a player through portals.
 */
public class FakeEntityManager {
    private final ServerPlayerEntity player;

    private final Map<UUID, Integer> realToFakeId = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> fakeToRealId = new ConcurrentHashMap<>();
    private final Set<UUID> shownFakeEntities = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> fakeEntityFlickerGuard = new ConcurrentHashMap<>();
    private int nextFakeEntityId = -1000000;
    private final Map<UUID, UUID> lastTickVehicleMap = new ConcurrentHashMap<>();
    private Map<UUID, Entity> entitiesToUpdateOnMainThread = new HashMap<>();

    private static final int FLICKER_GUARD_TICKS = 5;

    public FakeEntityManager(ServerPlayerEntity player) {
        this.player = player;
    }

    public void tick() {
        fakeEntityFlickerGuard.replaceAll((k, v) -> v - 1);
        fakeEntityFlickerGuard.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    public Map<UUID, Entity> getEntitiesToUpdateOnMainThread() {
        return entitiesToUpdateOnMainThread;
    }

    public Integer getFakeId(UUID realUuid) {
        return realToFakeId.get(realUuid);
    }

    public List<Packet<?>> getPurgePackets() {
        List<Packet<?>> packets = new ArrayList<>();
        if (!shownFakeEntities.isEmpty()) {
            int[] fakeIds = shownFakeEntities.stream()
                    .mapToInt(realToFakeId::get)
                    .toArray();
            if (fakeIds.length > 0) {
                packets.add(new EntitiesDestroyS2CPacket(fakeIds));
            }
        }
        return packets;
    }

    public void purgeAll() {
        realToFakeId.clear();
        fakeToRealId.clear();
        shownFakeEntities.clear();
        fakeEntityFlickerGuard.clear();
        lastTickVehicleMap.clear();
        entitiesToUpdateOnMainThread.clear();
    }

    public List<Packet<?>> process(Map<UUID, Entity> destinationEntityMap, List<Portal> portalsToProcess, Map<BlockPos, ViewFrustum> entityFrustumCache) {
        List<Packet<?>> packetsToSend = new ArrayList<>();
        final List<Packet<? super ClientPlayPacketListener>> bundledPackets = new ArrayList<>();
        Map<UUID, Entity> visibleRealEntities = new HashMap<>();
        Map<UUID, Portal> entityPortalContext = new HashMap<>();

        for (Entity realEntity : destinationEntityMap.values()) {
            for (Portal portal : portalsToProcess) {
                TransformProfile transformProfile = portal.getTransformProfile();
                if (transformProfile == null) continue;
                ViewFrustum viewFrustum = entityFrustumCache.computeIfAbsent(
                        portal.getLowerLeft(),
                        k -> new ViewFrustum(player.getEyePos(), portal)
                );
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
        Map<UUID, EntitySpawnS2CPacket> fakeEntitySpawnPackets = new HashMap<>();
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

        if (!entitiesToActuallyDestroy.isEmpty()) {
            int[] idsToDestroy = entitiesToActuallyDestroy.stream().mapToInt(uuid -> realToFakeId.getOrDefault(uuid, 0)).filter(id -> id != 0).toArray();
            if (idsToDestroy.length > 0) {
                bundledPackets.add(new EntitiesDestroyS2CPacket(idsToDestroy));
            }
            entitiesToActuallyDestroy.forEach(uuid -> fakeEntityFlickerGuard.put(uuid, FLICKER_GUARD_TICKS));
        }

        for (UUID uuid : entitiesToActuallyShow) {
            if (!shownFakeEntities.contains(uuid)) {
                bundledPackets.add(fakeEntitySpawnPackets.get(uuid));
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
            bundledPackets.add(new EntityPositionS2CPacket(fakeId, new PlayerPosition(fakePos, fakeVel, fakeYaw, realEntity.getPitch()), Collections.emptySet(), realEntity.isOnGround()));
            float fakeHeadYaw = transformProfile.untransformYaw(realEntity.getHeadYaw());
            byte headYawByte = (byte) MathHelper.floor(fakeHeadYaw * 256.0F / 360.0F);
            EntitySetHeadYawS2CPacket headYawPacket = new EntitySetHeadYawS2CPacket(realEntity, (byte)0);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setEntityId(fakeId);
            ((EntitySetHeadYawS2CPacketAccessor) headYawPacket).ic$setHeadYaw(headYawByte);
            bundledPackets.add(headYawPacket);

            if (isNew && realEntity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) realEntity;
                List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    equipmentList.add(Pair.of(slot, livingEntity.getEquippedStack(slot)));
                }
                if (!equipmentList.isEmpty()) {
                    bundledPackets.add(new EntityEquipmentUpdateS2CPacket(fakeId, equipmentList));
                }
            }
            if (isNew) {
                List<DataTracker.SerializedEntry<?>> trackedValues = realEntity.getDataTracker().getChangedEntries();
                if (trackedValues != null && !trackedValues.isEmpty()) {
                    bundledPackets.add(new EntityTrackerUpdateS2CPacket(fakeId, trackedValues));
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
                    bundledPackets.add(EntityPassengersSetS2CPacket.CODEC.decode(buf));
                }
            }
        }

        shownFakeEntities.clear();
        shownFakeEntities.addAll(entitiesToActuallyShow);
        this.entitiesToUpdateOnMainThread = new HashMap<>(visibleRealEntities);

        if (!bundledPackets.isEmpty()) {
            packetsToSend.add(new BundleS2CPacket(bundledPackets));
        }
        return packetsToSend;
    }
}
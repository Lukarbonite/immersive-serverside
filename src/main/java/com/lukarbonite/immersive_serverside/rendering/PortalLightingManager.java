package com.lukarbonite.immersive_serverside.rendering;

import com.lukarbonite.immersive_serverside.mixin.ChunkLightProviderAccessor;
import com.lukarbonite.immersive_serverside.mixin.LightStorageAccessor;
import com.lukarbonite.immersive_serverside.mixin.LightingProviderAccessor;
import com.lukarbonite.immersive_serverside.mixin.LightUpdateS2CPacketInvoker;
import com.lukarbonite.immersive_serverside.objects.AsyncWorldView;
import com.lukarbonite.immersive_serverside.objects.TransformProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightStorage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles calculating and creating lighting packets for portal views.
 */
public class PortalLightingManager {

    public static List<Packet<?>> calculateLighting(Map<BlockPos, TransformProfile> blockToProfileMap, AsyncWorldView sourceView, AsyncWorldView destinationView, Map<ChunkSectionPos, Pair<ChunkNibbleArray, ChunkNibbleArray>> outSectionLightData) {
        List<Packet<?>> packetsToSend = new ArrayList<>();
        final ServerWorld sourceWorld = sourceView.getWorld();
        final ServerWorld destWorld = destinationView.getWorld();
        final ServerLightingProvider sourceLightProvider = sourceWorld.getChunkManager().getLightingProvider();
        final LightingProviderAccessor lightProviderAccessor = (LightingProviderAccessor) sourceLightProvider;

        for (BlockPos sourcePos : blockToProfileMap.keySet()) {
            final TransformProfile profile = blockToProfileMap.get(sourcePos);
            if (profile == null) continue;

            final ChunkSectionPos sourceSectionPos = ChunkSectionPos.from(sourcePos);
            final Pair<ChunkNibbleArray, ChunkNibbleArray> lightArrays = outSectionLightData.computeIfAbsent(
                    sourceSectionPos,
                    (pos) -> {
                        ChunkLightProvider<?, ?> skyLightProvider = lightProviderAccessor.ic$getSkyLightProvider();
                        ChunkLightProvider<?, ?> blockLightProvider = lightProviderAccessor.ic$getBlockLightProvider();

                        ChunkNibbleArray sky = null;
                        if (skyLightProvider != null) {
                            LightStorageAccessor skyAccessor = (LightStorageAccessor) ((ChunkLightProviderAccessor) skyLightProvider).ic$getLightStorage();
                            sky = skyAccessor.ic$getLightSection(pos.asLong());
                        }

                        ChunkNibbleArray block = null;
                        if (blockLightProvider != null) {
                            LightStorageAccessor blockAccessor = (LightStorageAccessor) ((ChunkLightProviderAccessor) blockLightProvider).ic$getLightStorage();
                            block = blockAccessor.ic$getLightSection(pos.asLong());
                        }

                        return Pair.of(
                                sky != null ? sky.copy() : new ChunkNibbleArray(),
                                block != null ? block.copy() : new ChunkNibbleArray()
                        );
                    }
            );

            final BlockPos transformedPos = profile.transform(sourcePos);
            final int skyLight = destWorld.getLightLevel(LightType.SKY, transformedPos);
            final int blockLight = destWorld.getLightLevel(LightType.BLOCK, transformedPos);

            final short packedLocalPos = ChunkSectionPos.packLocal(sourcePos);
            final int localX = ChunkSectionPos.unpackLocalX(packedLocalPos);
            final int localY = ChunkSectionPos.unpackLocalY(packedLocalPos);
            final int localZ = ChunkSectionPos.unpackLocalZ(packedLocalPos);

            lightArrays.getFirst().set(localX, localY, localZ, skyLight);
            lightArrays.getSecond().set(localX, localY, localZ, blockLight);
        }

        final Map<ChunkPos, SortedMap<Integer, Pair<byte[], byte[]>>> chunkLightData = new HashMap<>();
        final int bottomYIndex = sourceWorld.getBottomSectionCoord();

        for (Map.Entry<ChunkSectionPos, Pair<ChunkNibbleArray, ChunkNibbleArray>> entry : outSectionLightData.entrySet()) {
            ChunkSectionPos sectionPos = entry.getKey();
            Pair<ChunkNibbleArray, ChunkNibbleArray> arrays = entry.getValue();

            chunkLightData
                    .computeIfAbsent(sectionPos.toChunkPos(), k -> new TreeMap<>())
                    .put(sectionPos.getY() - bottomYIndex, Pair.of(arrays.getFirst().asByteArray(), arrays.getSecond().asByteArray()));
        }

        for (Map.Entry<ChunkPos, SortedMap<Integer, Pair<byte[], byte[]>>> entry : chunkLightData.entrySet()) {
            final ChunkPos chunkPos = entry.getKey();
            final SortedMap<Integer, Pair<byte[], byte[]>> sortedSections = entry.getValue();

            final BitSet skyLightMask = new BitSet();
            final BitSet blockLightMask = new BitSet();
            final List<byte[]> skyLightUpdates = new ArrayList<>();
            final List<byte[]> blockLightUpdates = new ArrayList<>();

            for (Map.Entry<Integer, Pair<byte[], byte[]>> sectionEntry : sortedSections.entrySet()) {
                int yIndex = sectionEntry.getKey();
                Pair<byte[], byte[]> lightArrays = sectionEntry.getValue();

                skyLightMask.set(yIndex);
                blockLightMask.set(yIndex);
                skyLightUpdates.add(lightArrays.getFirst());
                blockLightUpdates.add(lightArrays.getSecond());
            }

            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeVarInt(chunkPos.x);
            buf.writeVarInt(chunkPos.z);
            buf.writeBitSet(skyLightMask);
            buf.writeBitSet(blockLightMask);
            buf.writeBitSet(new BitSet());
            buf.writeBitSet(new BitSet());
            buf.writeCollection(skyLightUpdates, (packetByteBuf, bytes) -> packetByteBuf.writeByteArray(bytes));
            buf.writeCollection(blockLightUpdates, (packetByteBuf, bytes) -> packetByteBuf.writeByteArray(bytes));

            packetsToSend.add(LightUpdateS2CPacketInvoker.ic$create(buf));
        }
        return packetsToSend;
    }

    public static List<Packet<?>> getRevertPackets(Set<ChunkSectionPos> purgedSections, ServerWorld sourceWorld) {
        List<Packet<?>> packets = new ArrayList<>();
        if (!purgedSections.isEmpty()) {
            final int bottomYIndex = sourceWorld.getBottomSectionCoord();
            final Map<ChunkPos, BitSet> sectionsToRevertByChunk = purgedSections.stream()
                    .collect(Collectors.groupingBy(
                            ChunkSectionPos::toChunkPos,
                            Collectors.mapping(
                                    sectionPos -> sectionPos.getY() - bottomYIndex,
                                    Collectors.collectingAndThen(Collectors.toList(), list -> {
                                        BitSet bitSet = new BitSet();
                                        list.forEach(bitSet::set);
                                        return bitSet;
                                    })
                            )
                    ));

            for (Map.Entry<ChunkPos, BitSet> entry : sectionsToRevertByChunk.entrySet()) {
                packets.add(new LightUpdateS2CPacket(entry.getKey(), sourceWorld.getChunkManager().getLightingProvider(), entry.getValue(), entry.getValue()));
            }
        }
        return packets;
    }
}
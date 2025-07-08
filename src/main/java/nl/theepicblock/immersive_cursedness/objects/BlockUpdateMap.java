package nl.theepicblock.immersive_cursedness.objects;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkSection;

public class BlockUpdateMap extends Long2ObjectOpenHashMap<Short2ObjectMap<BlockState>> {
    public void put(BlockPos p, BlockState t) {
        long cp = getChunkPos(p);
        Short2ObjectMap<BlockState> map = this.get(cp);
        if (map == null) {
            map = new Short2ObjectOpenHashMap<>();
            this.put(cp, map);
        }
        map.put(ChunkSectionPos.packLocal(p),t);
    }

    public BlockState get(BlockPos p) {
        Short2ObjectMap<BlockState> map = this.get(getChunkPos(p));
        if (map == null) return null;
        return map.get(ChunkSectionPos.packLocal(p));
    }

    public void sendTo(ServerPlayerEntity player) {
        Registry<Biome> biomeRegistry = player.getWorld().getRegistryManager().getOrThrow(RegistryKeys.BIOME);

        for (Long2ObjectMap.Entry<Short2ObjectMap<BlockState>> entry : this.long2ObjectEntrySet()) {
            ChunkSectionPos chunkSectionPos = ChunkSectionPos.from(entry.getLongKey());
            Short2ObjectMap<BlockState> chunkContents = entry.getValue();

            if (chunkContents.isEmpty()) {
                continue;
            }

            ShortSet positions = chunkContents.keySet();
            ChunkSection chunkSection = new ChunkSection(biomeRegistry);
            for (short pos : positions) {
                chunkSection.setBlockState(
                        ChunkSectionPos.unpackLocalX(pos),
                        ChunkSectionPos.unpackLocalY(pos),
                        ChunkSectionPos.unpackLocalZ(pos),
                        chunkContents.get(pos)
                );
            }

            player.networkHandler.sendPacket(new ChunkDeltaUpdateS2CPacket(chunkSectionPos, positions, chunkSection));
        }
    }

    private long getChunkPos(BlockPos p) {
        return ChunkSectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
    }
}
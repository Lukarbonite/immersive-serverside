package com.lukarbonite.immersive_serverside.objects;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class BlockCache {
    public final static int CHUNK_SIZE = 2;
    public final static int DEFAULT_MAP_SIZE = 16;
    public final static int DEFAULT_HASHMAP_SIZE = 256;
    private final Int2ObjectMap<Int2ObjectMap<Map<BlockPos,BlockState>>> cache = new Int2ObjectOpenHashMap<>(DEFAULT_MAP_SIZE);
    private int size = 0;

    public synchronized BlockState get(BlockPos p) {
        Int2ObjectMap<Map<BlockPos,BlockState>> chunkSlice = cache.get(p.getX() >> CHUNK_SIZE);
        if (chunkSlice == null) return null;

        Map<BlockPos,BlockState> chunk = chunkSlice.get(p.getZ() >> CHUNK_SIZE);
        if (chunk == null) return null;
        return chunk.get(p);
    }

    public synchronized void put(BlockPos p, BlockState t) {
        Int2ObjectMap<Map<BlockPos,BlockState>> chunkSlice = cache.get(p.getX() >> CHUNK_SIZE);
        if (chunkSlice == null) {
            chunkSlice = new Int2ObjectOpenHashMap<>(DEFAULT_MAP_SIZE);
            cache.put(p.getX() >> CHUNK_SIZE, chunkSlice);
        }

        Map<BlockPos,BlockState> chunk = chunkSlice.get(p.getZ() >> CHUNK_SIZE);
        if (chunk == null) {
            chunk = new HashMap<>(DEFAULT_HASHMAP_SIZE);
            chunkSlice.put(p.getZ() >> CHUNK_SIZE, chunk);
        }

        BlockState v = chunk.put(p, t);
        if (v == null) size++;
    }

    public synchronized int size() {
        return size;
    }

    /**
     * Iterates the entire cache, removing any entry not contained in the provided set.
     * This is inefficient and should be used sparingly.
     * @param blocksToKeep The set of blocks that should NOT be purged.
     * @param onRemove A callback for each removed entry.
     */
    public synchronized void purgeAllExcept(Set<BlockPos> blocksToKeep, BiConsumer<BlockPos, BlockState> onRemove) {
        var sliceIterator = cache.int2ObjectEntrySet().iterator();
        while (sliceIterator.hasNext()) {
            var sliceEntry = sliceIterator.next();
            var cacheSlice = sliceEntry.getValue();

            var mapIterator = cacheSlice.int2ObjectEntrySet().iterator();
            while (mapIterator.hasNext()) {
                var mapEntry = mapIterator.next();
                Map<BlockPos, BlockState> map = mapEntry.getValue();

                map.entrySet().removeIf((entry) -> {
                    BlockPos mapBlockPos = entry.getKey();
                    if (blocksToKeep.contains(mapBlockPos)) {
                        return false; // Don't purge if it's currently in view
                    }
                    onRemove.accept(mapBlockPos, entry.getValue());
                    size--;
                    return true;
                });

                if (map.isEmpty()) {
                    mapIterator.remove();
                }
            }

            if (cacheSlice.isEmpty()) {
                sliceIterator.remove();
            }
        }
    }

    /**
     * Efficiently purges a specific set of block positions from the cache.
     * @param positionsToPurge The set of blocks to remove.
     * @param onRemove A callback for each removed entry.
     */
    public synchronized void purgePositions(Set<BlockPos> positionsToPurge, BiConsumer<BlockPos, BlockState> onRemove) {
        for (BlockPos pos : positionsToPurge) {
            int chunkX = pos.getX() >> CHUNK_SIZE;
            int chunkZ = pos.getZ() >> CHUNK_SIZE;
            Int2ObjectMap<Map<BlockPos, BlockState>> chunkSlice = cache.get(chunkX);
            if (chunkSlice == null) continue;

            Map<BlockPos, BlockState> chunk = chunkSlice.get(chunkZ);
            if (chunk == null) continue;

            BlockState removedState = chunk.remove(pos);
            if (removedState != null) {
                onRemove.accept(pos, removedState);
                size--;
                if (chunk.isEmpty()) {
                    chunkSlice.remove(chunkZ);
                    if (chunkSlice.isEmpty()) {
                        cache.remove(chunkX);
                    }
                }
            }
        }
    }


    private void purge(Int2ObjectMap<Map<BlockPos,BlockState>> v, BiConsumer<BlockPos, BlockState> onRemove) {
        v.values().forEach((map) -> purge(map, onRemove));
    }

    private void purge(Map<BlockPos,BlockState> v, BiConsumer<BlockPos, BlockState> onRemove) {
        size -= v.size();
        v.forEach(onRemove);
    }

    public synchronized void purgeAll(BiConsumer<BlockPos, BlockState> onRemove) {
        cache.values().forEach((slice) -> purge(slice, onRemove));
        cache.clear();
        size=0;
    }
}
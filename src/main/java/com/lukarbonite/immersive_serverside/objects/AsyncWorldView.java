package com.lukarbonite.immersive_serverside.objects;

import com.lukarbonite.immersive_serverside.Util;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AsyncWorldView implements BlockView {
    private final Map<ChunkPos, Chunk> chunkCache = new HashMap<>();
    private final ServerWorld world;
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    public AsyncWorldView(ServerWorld world) {
        this.world = world;
    }

    public ServerWorld getWorld() {
        return this.world;
    }

    public BlockState getBlock(BlockPos pos) {
        Chunk chunk = getChunk(pos);
        if (chunk == null) return AIR;

        return chunk.getBlockState(pos);
    }

    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        Chunk chunk = getChunk(pos);
        if (chunk == null) return null;

        return chunk.getBlockEntity(pos);
    }

    public Chunk getChunk(BlockPos p) {
        return getChunk(new ChunkPos(p));
    }

    public Chunk getChunk(ChunkPos chunkPos) {
        Chunk chunk = this.chunkCache.get(chunkPos);
        if (chunk != null) {
            return chunk;
        }

        // Exclusively use the non-blocking method.
        // If a chunk is not immediately available, skip rendering for that portion for this tick.
        Optional<OptionalChunk<Chunk>> chunkOpt = Util.tryGetChunkAsync(this.world, chunkPos.x, chunkPos.z);
        if (chunkOpt.isEmpty()) {
            return null;
        }

        OptionalChunk<Chunk> chunkO = chunkOpt.get();

        if (chunkO.isPresent()) {
            chunk = chunkO.orElseThrow(NullPointerException::new);
            this.chunkCache.put(chunkPos, chunk);
        }
        return chunk;
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getBlock(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }
}
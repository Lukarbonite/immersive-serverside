package nl.theepicblock.immersive_cursedness.objects;

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
import nl.theepicblock.immersive_cursedness.Util;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AsyncWorldView implements BlockView {
	private final Map<ChunkPos, Chunk> chunkCache = new HashMap<>();
	private final ServerWorld world;
	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private final boolean nonBlocking;

	public AsyncWorldView(ServerWorld world) {
		this(world, false);
	}

	public AsyncWorldView(ServerWorld world, boolean nonBlocking) {
		this.world = world;
		this.nonBlocking = nonBlocking;
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

		OptionalChunk<Chunk> chunkO;
		if (this.nonBlocking) {
			Optional<OptionalChunk<Chunk>> chunkOpt = Util.tryGetChunkAsync(this.world, chunkPos.x, chunkPos.z);
			if (chunkOpt.isEmpty()) return null;
			chunkO = chunkOpt.get();
		} else {
			chunkO = Util.getChunkAsync(this.world, chunkPos.x, chunkPos.z);
		}

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
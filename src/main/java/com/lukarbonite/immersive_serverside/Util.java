package com.lukarbonite.immersive_serverside;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import com.lukarbonite.immersive_serverside.mixin.ServerChunkManagerInvoker;
import com.lukarbonite.immersive_serverside.objects.TransformProfile;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class Util {
    public static int get(BlockPos b, Direction.Axis axis) {
        return axis.choose(b.getX(), b.getY(), b.getZ());
    }

    public static double get(Vec3d b, Direction.Axis axis) {
        return axis.choose(b.getX(), b.getY(), b.getZ());
    }

    public static void set(BlockPos.Mutable b, int i, Direction.Axis axis) {
        switch (axis) {
            case X -> b.setX(i);
            case Y -> b.setY(i);
            case Z -> b.setZ(i);
        }
    }

    public static Direction.Axis rotate(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
            default -> axis;
        };
    }

    public static void sendParticle(ServerPlayerEntity player, Vec3d pos, float r, float g, float b) {
        // Particles remain disabled due to API changes.
    }

    public static Vec3d getCenter(BlockPos p) {
        return p.toCenterPos();
    }

    public static OptionalChunk<Chunk> getChunkAsync(ServerWorld world, int x, int z) {
        ServerChunkManagerInvoker chunkManager = (ServerChunkManagerInvoker) world.getChunkManager();
        return chunkManager.ic$callGetChunkFuture(x, z, ChunkStatus.FULL, false).join();
    }

    public static Optional<OptionalChunk<Chunk>> tryGetChunkAsync(ServerWorld world, int x, int z) {
        ServerChunkManagerInvoker chunkManager = (ServerChunkManagerInvoker) world.getChunkManager();
        CompletableFuture<OptionalChunk<Chunk>> future = chunkManager.ic$callGetChunkFuture(x, z, ChunkStatus.FULL, false);
        if (future.isDone()) {
            return Optional.of(future.getNow(null));
        }
        return Optional.empty();
    }

    public static ServerWorld getDestination(ServerWorld serverWorld) {
        var minecraftServer = serverWorld.getServer();
        var registryKey = serverWorld.getRegistryKey() == World.NETHER ? World.OVERWORLD : World.NETHER;
        return minecraftServer.getWorld(registryKey);
    }

    public static double getDistance(BlockPos a, BlockPos b) {
        return a.getSquaredDistance(b);
    }

    public static PlayerManager getManagerFromPlayer(ServerPlayerEntity player) {
        if (ImmersiveServerside.serversideServer == null) return null;
        return ImmersiveServerside.serversideServer.getManager(player);
    }

    public static WorldHeights calculateMinMax(HeightLimitView source, HeightLimitView destination, TransformProfile t) {
        int lower = source.getBottomY();
        int top = source.getTopYInclusive();
        int destinationLower = t.transformYOnly(lower);
        int destinationTop = t.transformYOnly(top);
        destinationLower = Math.max(destinationLower, destination.getBottomY());
        destinationTop = Math.min(destinationTop, destination.getTopYInclusive());
        destinationLower = t.unTransformYOnly(destinationLower);
        destinationTop = t.unTransformYOnly(destinationTop);

        return new WorldHeights(Math.max(lower, destinationLower), Math.min(top, destinationTop));
    }

    public record WorldHeights(int min, int max) {}

    public static BlockBox getBoundingBox(List<BlockPos> positions) {
        return BlockBox.encompassPositions(positions).orElseThrow(() -> new IllegalArgumentException("Cannot create bounding box from empty list"));
    }

    public static BlockPos getCanonicalPos(List<BlockPos> positions) {
        BlockBox box = getBoundingBox(positions);
        return new BlockPos(box.getMinX(), box.getMinY(), box.getMinZ());
    }

    @Nullable
    public static Packet<ClientPlayPacketListener> createFakeBlockEntityPacket(BlockEntity entity, BlockPos pos, World playerWorld) {
        // The factory method requires the BlockEntity itself.
        // We create a temporary one to ensure the world context is correct for NBT generation.
        NbtCompound nbt = entity.createNbtWithIdentifyingData(playerWorld.getRegistryManager());
        BlockEntity fakeEntity = BlockEntity.createFromNbt(pos, entity.getCachedState(), nbt, playerWorld.getRegistryManager());
        if (fakeEntity == null) {
            return null;
        }
        fakeEntity.setWorld(playerWorld);
        return fakeEntity.toUpdatePacket();
    }


    public static Packet<?> createEntityHidePacket(int entityId) {
        return new EntitiesDestroyS2CPacket(entityId);
    }
}
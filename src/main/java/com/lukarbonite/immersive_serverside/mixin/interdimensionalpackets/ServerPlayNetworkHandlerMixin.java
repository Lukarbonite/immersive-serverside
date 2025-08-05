package com.lukarbonite.immersive_serverside.mixin.interdimensionalpackets;

import com.lukarbonite.immersive_serverside.PlayerInterface;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import com.lukarbonite.immersive_serverside.PlayerManager;
import com.lukarbonite.immersive_serverside.Util;
import com.lukarbonite.immersive_serverside.objects.TransformProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
	@Shadow public ServerPlayerEntity player;
	@Unique private TransformProfile immersivecursedness$transformProfile;

	@Inject(method = "onPlayerInteractBlock", at = @At("HEAD"))
	private void captureTransformProfile(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
		immersivecursedness$transformProfile = null;
		if (((PlayerInterface) this.player).immersivecursedness$getCloseToPortal()) {
			PlayerManager manager = Util.getManagerFromPlayer(player);
			if (manager != null) {
				BlockHitResult blockHitResult = packet.getBlockHitResult();
				var checkPos = blockHitResult.getBlockPos().offset(blockHitResult.getSide());
				immersivecursedness$transformProfile = manager.getTransformProfileForBlock(checkPos);
			}
		}
	}

	// Corrected the parameter type from ServerWorld to World
	@ModifyArg(method = "onPlayerInteractBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"), index = 1)
	public World modifyWorld(World original) {
		if (immersivecursedness$transformProfile != null) {
			return Util.getDestination((ServerWorld)original);
		}
		return original;
	}

	@ModifyArg(method = "onPlayerInteractBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"), index = 4)
	public BlockHitResult modifyUse(BlockHitResult original) {
		if (immersivecursedness$transformProfile != null) {
			var transformedVec = immersivecursedness$transformProfile.transform(original.getPos());
			var transformedBlockPos = immersivecursedness$transformProfile.transform(original.getBlockPos());
			var rotatedSide = immersivecursedness$transformProfile.rotate(original.getSide());
			return new BlockHitResult(transformedVec, rotatedSide, transformedBlockPos, original.isInsideBlock());
		}
		return original;
	}

	@Inject(method = "onPlayerInteractBlock", at = @At("RETURN"))
	public void clearTransformProfile(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
		immersivecursedness$transformProfile = null;
	}
}
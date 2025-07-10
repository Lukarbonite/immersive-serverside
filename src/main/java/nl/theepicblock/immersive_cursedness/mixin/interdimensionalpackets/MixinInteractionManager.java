package nl.theepicblock.immersive_cursedness.mixin.interdimensionalpackets;

import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import nl.theepicblock.immersive_cursedness.PlayerInterface;
import nl.theepicblock.immersive_cursedness.PlayerManager;
import nl.theepicblock.immersive_cursedness.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerInteractionManager.class)
public class MixinInteractionManager {
	@Shadow public ServerPlayerEntity player;
	@Shadow public ServerWorld world;
	@Unique private BlockPos immersivecursedness$transformedPos = null;

	// Corrected the signature to include 'worldHeight'
	@Inject(method = "processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V", at = @At("HEAD"))
	public void captureAndTransformBreakPos(BlockPos pos, PlayerActionC2SPacket.Action action, Direction direction, int worldHeight, int sequence, CallbackInfo ci) {
		immersivecursedness$transformedPos = null;
		if (((PlayerInterface) player).immersivecursedness$getCloseToPortal()) {
			PlayerManager manager = Util.getManagerFromPlayer(player);
			if (manager != null) {
				var profile = manager.getTransformProfileForBlock(pos);
				if (profile != null) {
					this.world = Util.getDestination(this.world);
					immersivecursedness$transformedPos = profile.transform(pos);
				}
			}
		}
	}

	@ModifyVariable(method = "processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V", at = @At("HEAD"), argsOnly = true, index = 1)
	private BlockPos modifyBreakPos(BlockPos original) {
		if (immersivecursedness$transformedPos != null) {
			return immersivecursedness$transformedPos;
		}
		return original;
	}

	@Inject(method = "processBlockBreakingAction(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket$Action;Lnet/minecraft/util/math/Direction;II)V", at = @At("RETURN"))
	public void restoreWorld(BlockPos pos, PlayerActionC2SPacket.Action action, Direction direction, int worldHeight, int sequence, CallbackInfo ci) {
		this.world = this.player.getWorld();
		immersivecursedness$transformedPos = null;
	}
}
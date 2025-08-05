package com.lukarbonite.immersive_serverside.mixin;

import com.lukarbonite.immersive_serverside.PlayerInterface;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {
	@Inject(method = "getPortalDelay", at = @At("HEAD"), cancellable = true)
	private void getPortalDelay(ServerWorld world, Entity entity, CallbackInfoReturnable<Integer> cir) {
		if (!(entity instanceof PlayerInterface))
			return;

		if (((PlayerInterface)entity).immersivecursedness$getEnabled())
			cir.setReturnValue(1);
	}
}

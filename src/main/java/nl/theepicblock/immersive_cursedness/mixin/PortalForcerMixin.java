package nl.theepicblock.immersive_cursedness.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.dimension.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PortalForcer.class)
public class PortalForcerMixin {
	@Shadow @Final private ServerWorld world;

	// This mixin is kept for now, but the dangerous flag has been removed.
	// The logic inside DummyEntity to find a portal target is now expected to be called
	// from the main server thread, where direct world access is safe.
	// If future changes move this logic back to the helper thread, a thread-safe
	// world view (like AsyncWorldView) must be used here instead of direct access.
}
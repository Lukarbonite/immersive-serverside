package nl.theepicblock.immersive_cursedness;

import net.minecraft.server.network.ServerPlayerEntity;

public interface PlayerInterface {
	void immersivecursedness$setCloseToPortal(boolean v);
	boolean immersivecursedness$getCloseToPortal();

	static boolean isCloseToPortal(ServerPlayerEntity player) {
		return ((PlayerInterface)player).immersivecursedness$getCloseToPortal();
	}

	void immersivecursedness$setEnabled(boolean v);
	boolean immersivecursedness$getEnabled();
}
package nl.theepicblock.immersive_cursedness.mixin;

import com.mojang.authlib.GameProfile;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.world.World;
import nl.theepicblock.immersive_cursedness.IC_Config;
import nl.theepicblock.immersive_cursedness.PlayerInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class MixinServerPlayerEntity extends PlayerEntity implements PlayerInterface {
	@Unique
	private volatile boolean isCloseToPortal;
	@Unique
	private boolean ic_enabled = true; // Default value

	// The super constructor has changed, so we cannot define our own.
	// We will inject into the existing constructor if initialization is needed,
	// but initializing at declaration is sufficient here.
	public MixinServerPlayerEntity(World world, GameProfile profile) {
		super(world, profile);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void onInit(CallbackInfo ci) {
		// Initialize based on config when the player is first created.
		this.ic_enabled = AutoConfig.getConfigHolder(IC_Config.class).getConfig().defaultEnabled;
	}

	@Override
	public void immersivecursedness$setCloseToPortal(boolean v) {
		isCloseToPortal = v;
	}

	@Override
	public boolean immersivecursedness$getCloseToPortal() {
		return isCloseToPortal;
	}

	// Correctly target writeCustomData(WriteView) which is inherited from Entity
	@Inject(method = "writeCustomData(Lnet/minecraft/storage/WriteView;)V", at = @At("RETURN"))
	public void writeNbt(WriteView view, CallbackInfo ci) {
		view.putBoolean("immersivecursedness_enabled", ic_enabled);
	}

	// Correctly target readCustomData(ReadView) which is inherited from Entity
	@Inject(method = "readCustomData(Lnet/minecraft/storage/ReadView;)V", at = @At("RETURN"))
	public void readNbt(ReadView view, CallbackInfo ci) {
		boolean defaultEnabled = AutoConfig.getConfigHolder(IC_Config.class).getConfig().defaultEnabled;
		// The getBoolean method with a default value is the safest way to read.
		this.ic_enabled = view.getBoolean("immersivecursedness_enabled", defaultEnabled);
	}

	@Override
	public void immersivecursedness$setEnabled(boolean v) {
		ic_enabled = v;
	}

	@Override
	public boolean immersivecursedness$getEnabled() {
		return ic_enabled;
	}
}
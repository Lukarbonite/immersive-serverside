package com.lukarbonite.immersive_serverside.mixin;

import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.LightStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LightStorage.class)
public interface LightStorageAccessor {
    @Invoker("getLightSection")
    ChunkNibbleArray ic$getLightSection(long sectionPos);
}
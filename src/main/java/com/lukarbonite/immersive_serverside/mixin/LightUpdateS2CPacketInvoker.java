package com.lukarbonite.immersive_serverside.mixin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LightUpdateS2CPacket.class)
public interface LightUpdateS2CPacketInvoker {
    @Invoker("<init>")
    static LightUpdateS2CPacket ic$create(PacketByteBuf buf) {
        throw new AssertionError("This should not be called directly.");
    }
}
package com.lukarbonite.immersive_serverside.mixin;

import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntitySetHeadYawS2CPacket.class)
public interface EntitySetHeadYawS2CPacketAccessor {
    @Accessor("entityId")
    @Mutable
    void ic$setEntityId(int id);

    @Accessor("headYaw")
    @Mutable
    void ic$setHeadYaw(byte yaw);
}
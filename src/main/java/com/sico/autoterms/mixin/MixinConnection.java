package com.sico.autoterms.mixin;

import com.sico.autoterms.SicoAutoTerms;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The terminal board is built off the wire, on the netty thread, the moment a packet is decoded: the slot update that
 * acknowledges a click is the signal the cadence runs on, and it should not wait a frame for the client thread.
 *
 * <p>{@code Connection} is both ends of a singleplayer world; only the client's own connection (the one that receives
 * {@link PacketFlow#CLIENTBOUND}) is read.
 */
@Mixin(Connection.class)
public class MixinConnection {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void sicoautoterms$read(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod == null || ((Connection) (Object) this).getReceiving() != PacketFlow.CLIENTBOUND) {
            return;
        }
        if (packet instanceof ClientboundPingPacket) {
            mod.clock().onPing(System.nanoTime());
        }
        mod.autoTerms().onPacketReceived(packet);
    }

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"))
    private void sicoautoterms$send(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        SicoAutoTerms mod = SicoAutoTerms.instance();
        if (mod != null && ((Connection) (Object) this).getReceiving() == PacketFlow.CLIENTBOUND) {
            mod.autoTerms().onPacketSend(packet);
        }
    }
}

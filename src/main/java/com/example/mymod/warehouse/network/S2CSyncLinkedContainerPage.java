package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.menu.LinkedContainerMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * 服务端 → 客户端: 已连接容器页码同步. server 端 {@link LinkedContainerMenu#setPage}
 * 后会发这个包, 客户端在已打开的 LinkedContainerMenu 上同步 page 状态.
 */
public record S2CSyncLinkedContainerPage(int page) implements CustomPacketPayload {
    public static final Type<S2CSyncLinkedContainerPage> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_linked_page"));
    public static final StreamCodec<ByteBuf, S2CSyncLinkedContainerPage> STREAM_CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeInt(payload.page),
            buf -> new S2CSyncLinkedContainerPage(buf.readInt())
        );

    public static final IPayloadHandler<S2CSyncLinkedContainerPage> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.player.containerMenu instanceof LinkedContainerMenu menu) {
                menu.setPage(payload.page());
            }
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

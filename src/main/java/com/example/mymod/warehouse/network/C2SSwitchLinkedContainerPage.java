package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.menu.LinkedContainerMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * 客户端 → 服务端: 玩家在已连接容器界面里点 "&lt;" / "&gt;" 翻页.
 */
public record C2SSwitchLinkedContainerPage(int page) implements CustomPacketPayload {
    public static final Type<C2SSwitchLinkedContainerPage> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "switch_linked_page"));
    public static final StreamCodec<ByteBuf, C2SSwitchLinkedContainerPage> STREAM_CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeInt(payload.page),
            buf -> new C2SSwitchLinkedContainerPage(buf.readInt())
        );

    public static final IPayloadHandler<C2SSwitchLinkedContainerPage> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.containerMenu instanceof LinkedContainerMenu menu)) return;
            menu.setPage(payload.page());
            // server 端 page 改完后, 把新 page 同步给 client (主要是为了在断线重连
            // / 状态恢复时能拿到正确的 page)
            WarehouseNetworking.sendTo(sp, new S2CSyncLinkedContainerPage(menu.currentPage()));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * 客户端 → 服务端: 玩家在仓库主界面点了仓库本体分页的 "&lt;" / "&gt;".
 */
public record C2SSwitchWarehousePage(int page) implements CustomPacketPayload {
    public static final Type<C2SSwitchWarehousePage> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "switch_warehouse_page"));
    public static final StreamCodec<FriendlyByteBuf, C2SSwitchWarehousePage> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SSwitchWarehousePage::page,
            C2SSwitchWarehousePage::new
        );

    public static final IPayloadHandler<C2SSwitchWarehousePage> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.containerMenu instanceof com.example.mymod.warehouse.menu.WarehouseMenu menu)) return;
            menu.setPage(payload.page());
            WarehouseNetworking.sendTo(sp, new S2CSyncWarehousePage(menu.currentPage()));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

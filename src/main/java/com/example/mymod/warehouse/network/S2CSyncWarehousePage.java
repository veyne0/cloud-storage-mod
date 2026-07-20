package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * 服务端 → 客户端: 把当前仓库本体的页码同步给客户端.
 * <p>
 * 客户端收到后, 调 {@code WarehouseMenu.setPage(page)} 切到对应页, 槽位
 * 状态变化由标准容器同步下发.
 */
public record S2CSyncWarehousePage(int page) implements CustomPacketPayload {
    public static final Type<S2CSyncWarehousePage> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_warehouse_page"));
    public static final StreamCodec<FriendlyByteBuf, S2CSyncWarehousePage> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSyncWarehousePage::page,
            S2CSyncWarehousePage::new
        );

    public static final IPayloadHandler<S2CSyncWarehousePage> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.player.containerMenu instanceof com.example.mymod.warehouse.menu.WarehouseMenu menu) {
                menu.setPage(payload.page());
                // 切页后重建按钮 (< / > 启用/禁用状态)
                if (mc.screen instanceof com.example.mymod.warehouse.screen.WarehouseScreen ws) {
                    ws.refreshWidgets();
                }
            }
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

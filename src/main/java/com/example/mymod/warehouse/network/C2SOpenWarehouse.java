package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.WarehouseDataManager;
import com.example.mymod.warehouse.menu.WarehouseMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/** 客户端按 V → 服务端开仓库菜单. 无字段. */
public record C2SOpenWarehouse() implements CustomPacketPayload {
    public static final Type<C2SOpenWarehouse> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_warehouse"));
    public static final StreamCodec<FriendlyByteBuf, C2SOpenWarehouse> STREAM_CODEC =
        StreamCodec.unit(new C2SOpenWarehouse());

    public static final IPayloadHandler<C2SOpenWarehouse> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // 先把每个已连接容器的第一页前 9 格物品 (preview) 重新填一次,
            // 让仓库 UI 右侧悬浮 tooltip 显示的是最新内容 (而不是登录时那次).
            var data = WarehouseDataManager.get(sp);
            WarehouseDataManager.sendSyncLinkedContainers(sp, data);
            // vanilla 的 openMenu 会自动给客户端发 S2C 通知开屏
            sp.openMenu(new WarehouseMenu.Provider());
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

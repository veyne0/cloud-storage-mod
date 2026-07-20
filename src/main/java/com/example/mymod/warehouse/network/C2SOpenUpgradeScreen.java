package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * 客户端 → 服务端: 玩家在仓库主界面点了 "升级" 按钮, 请服务端把当前升级状态同步过来,
 * 然后客户端会再开 UpgradeScreen (用一个轻量的全屏界面).
 * <p>
 * 单独发这个包的原因: 升级状态 (等级 / 进度) 在服务端, 客户端要拿到最新数据才能画
 * 进度条 / 收 / 退按钮. 一开始按按钮就先把状态同步过来, 然后开屏.
 */
public record C2SOpenUpgradeScreen() implements CustomPacketPayload {
    public static final Type<C2SOpenUpgradeScreen> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_upgrade"));
    public static final StreamCodec<FriendlyByteBuf, C2SOpenUpgradeScreen> STREAM_CODEC =
        StreamCodec.unit(new C2SOpenUpgradeScreen());

    public static final IPayloadHandler<C2SOpenUpgradeScreen> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            // 先同步一份最新升级状态给客户端, 再开屏
            S2CSyncUpgradeState.sendTo(sp);
            // 客户端真正打开屏幕: 在 S2CSyncUpgradeState.HANDLER 里检测 Minecraft.screen
            // 是不是 WarehouseScreen, 是的话再 setScreen UpgradeScreen. 这样保证状态先到位.
            // 但更直接的做法是让客户端在 S2CSyncUpgradeState 到达后, 再决定是否开屏.
            // 简化: 我们把"开屏"的指令也用 S2CSyncUpgradeState 一起带 — 客户端只要收到
            // 本包触发的 S2CSyncUpgradeState 就开屏.
            // 为此我们用一个静态标记 (单玩家).
            com.example.mymod.warehouse.client.ClientWarehouseCache.requestOpenUpgrade = true;
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

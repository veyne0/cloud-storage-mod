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
 * 客户端 → 服务端: 玩家在升级界面点了 "提交材料" 按钮.
 * <p>
 * 服务端从玩家背包按 WarehouseLevel.next().requirements 逐项扣, 每种扣到目标上限就跳过.
 * 扣完若所有材料都达标, 自动升级 + 清空进度, 然后 S2CSyncUpgradeState 发回客户端.
 */
public record C2SSubmitUpgradeMaterials() implements CustomPacketPayload {
    public static final Type<C2SSubmitUpgradeMaterials> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "submit_upgrade"));
    public static final StreamCodec<FriendlyByteBuf, C2SSubmitUpgradeMaterials> STREAM_CODEC =
        StreamCodec.unit(new C2SSubmitUpgradeMaterials());

    public static final IPayloadHandler<C2SSubmitUpgradeMaterials> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            if (data.getLevelDef().isMax()) return; // 满级

            // 从玩家背包扣材料
            boolean allDone = data.submitUpgradeMaterials(sp.getInventory());
            if (allDone) {
                data.completeUpgrade();
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§a[云存储] 升级成功! 现在是 §eLv." + data.getLevel()
                        + "§a. 容器上限 " + data.getMaxLinkedContainers()
                        + ", 仓库 " + data.getPageCount() + " 页 (" + data.getTotalSlots() + " 格)"));
            } else {
                // 提示: 还差多少
                var next = data.getLevelDef().next();
                var prog = data.getUpgradeProgress();
                var missing = new StringBuilder();
                for (var e : next.requirements.entrySet()) {
                    int have = prog.getOrDefault(e.getKey(), 0);
                    int need = e.getValue() - have;
                    if (need > 0) {
                        if (missing.length() > 0) missing.append(", ");
                        missing.append("§f").append(e.getKey().getDescription().getString())
                               .append(" §7x").append(need);
                    }
                }
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§e[云存储] 已提交部分材料, 还差: " + missing));
            }
            WarehouseDataManager.setDirty();
            // 发回最新状态 (等级 / 进度)
            S2CSyncUpgradeState.sendTo(sp);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

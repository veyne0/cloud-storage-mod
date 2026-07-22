package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.LinkedChunkLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在编辑界面点了"解除连接", 服务端把这条 link 从玩家数据里删掉.
 * <p>
 * 删除后立即给所有相关客户端同步一份新的 linkedContainers 列表.
 * 同时摘掉 {@link LinkedChunkLoader} 里的 PERSISTENT chunk ticket, 让该 chunk
 * 在玩家离开后能正常卸载 (不再强制占用内存和 CPU).
 */
public record C2SUnlinkLinkedContainer(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SUnlinkLinkedContainer> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "unlink_linked"));
    public static final StreamCodec<FriendlyByteBuf, C2SUnlinkLinkedContainer> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC,
            C2SUnlinkLinkedContainer::linkId,
            C2SUnlinkLinkedContainer::new
        );

    public static final IPayloadHandler<C2SUnlinkLinkedContainer> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var data = com.example.mymod.warehouse.WarehouseDataManager.get(sp);
            var link = data.findLink(payload.linkId);
            if (link == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[云存储] 这个连接已经不存在了"));
                return;
            }
            String name = link.name();
            data.removeLink(payload.linkId);
            // 摘 PERSISTENT ticket — 这是连接解除时唯一的"反注册"点
            LinkedChunkLoader.remove(payload.linkId);
            com.example.mymod.warehouse.WarehouseDataManager.setDirty();
            com.example.mymod.warehouse.WarehouseDataManager.sendSyncLinkedContainers(sp, data);
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a[云存储] 已解除连接: " + name));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

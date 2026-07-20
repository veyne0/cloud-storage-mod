package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.WarehouseDataManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 提交玩家在命名框里写的名字.
 */
public record C2SSubmitContainerName(UUID linkId, String name) implements CustomPacketPayload {
    public static final Type<C2SSubmitContainerName> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "submit_name"));
    public static final StreamCodec<FriendlyByteBuf, C2SSubmitContainerName> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, C2SSubmitContainerName::linkId,
            ByteBufCodecs.STRING_UTF8, C2SSubmitContainerName::name,
            C2SSubmitContainerName::new
        );

    public static final IPayloadHandler<C2SSubmitContainerName> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var data = WarehouseDataManager.get(sp);
            var link = data.findLink(payload.linkId);
            if (link == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[云存储] 这个连接已经不存在了"));
                return;
            }
            String name = payload.name == null ? "" : payload.name.trim();
            if (name.isEmpty()) name = link.name();
            link.setName(name);
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncLinkedContainers(sp, data);
            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a[云存储] 已重命名为: " + name));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

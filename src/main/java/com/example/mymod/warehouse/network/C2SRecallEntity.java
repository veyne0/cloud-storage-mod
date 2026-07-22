package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在仓库 UI 点实体图标的"召回"按钮.
 * <p>
 * 服务端: 找到世界里 linkId 标记的实体 → 存回 NBT → 删除实体 → link.summoned = false.
 * 详见 {@link C2SSummonEntity#recallToLink}.
 */
public record C2SRecallEntity(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SRecallEntity> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "recall_entity"));
    public static final StreamCodec<ByteBuf, C2SRecallEntity> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            C2SRecallEntity::linkId,
            C2SRecallEntity::new
        );

    public static final IPayloadHandler<C2SRecallEntity> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            EntityLink link = data.findEntityLink(payload.linkId());
            if (link == null) return;
            C2SSummonEntity.recallToLink(sp, link);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

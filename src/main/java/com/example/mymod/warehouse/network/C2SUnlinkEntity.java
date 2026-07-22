package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在实体编辑界面点"解除连接".
 * <p>
 * 行为: 解除前先把实体召唤出来 (如果之前存储中), 然后从 link 列表里删掉.
 * 召回功能上等同"先 recall 再 unlink", 但无需"实体在世界里"这个前提.
 */
public record C2SUnlinkEntity(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SUnlinkEntity> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "unlink_entity"));
    public static final StreamCodec<ByteBuf, C2SUnlinkEntity> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            C2SUnlinkEntity::linkId,
            C2SUnlinkEntity::new
        );

    public static final IPayloadHandler<C2SUnlinkEntity> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            EntityLink link = data.findEntityLink(payload.linkId());
            if (link == null) return;

            // 1. 如果还在存储中, 先召唤出实体
            Entity summonedEntity;
            if (!link.summoned()) {
                // 触发一次召唤
                C2SSummonEntity.summonFromLink(sp, link);
                summonedEntity = C2SSummonEntity.findSummonedEntity(sp.serverLevel(), link.linkId());
            } else {
                // 已经召唤过, 检查实体是否还在
                summonedEntity = C2SSummonEntity.findSummonedEntity(sp.serverLevel(), link.linkId());
                if (summonedEntity == null) {
                    // 实体丢了, 重新召唤
                    C2SSummonEntity.summonFromLink(sp, link);
                    summonedEntity = C2SSummonEntity.findSummonedEntity(sp.serverLevel(), link.linkId());
                }
            }

            // 2. 清理被解除实体的"云端标记" — 让它彻底变回普通实体
            //    (解除连接 = 实体回归野生, 不再有 Glowing / WarehouseLinkId 约束,
            //     玩家可以用收容器重新收它, 也可以自由杀掉.)
            if (summonedEntity != null) {
                net.minecraft.nbt.CompoundTag persistent = summonedEntity.getPersistentData();
                if (persistent.hasUUID("WarehouseLinkId")) {
                    persistent.remove("WarehouseLinkId");
                }
                if (summonedEntity.getTags().contains("WarehouseSummoned")) {
                    summonedEntity.removeTag("WarehouseSummoned");
                }
                summonedEntity.setGlowingTag(false);
            }

            // 3. 从 link 列表删掉
            data.removeEntityLink(payload.linkId());
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncEntityLinks(sp, data);

            sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "\u00a7a[\u4e91\u5b58\u50a8] \u5df2\u89e3\u9664\u6536\u5bb9 \"" + link.getDisplayName().getString() + "\", \u5b9e\u4f53\u5df2\u8fd4\u56de\u4e16\u754c"));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在实体编辑界面改完名, 按"保存"按钮.
 * <p>
 * 服务端: 找 link → 改 name → 标脏 → 同步. 名字可以为空 (清空), 显示时 fallback 到实体类型名.
 */
public record C2SSaveEntityName(UUID linkId, String name) implements CustomPacketPayload {
    public static final Type<C2SSaveEntityName> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "save_entity_name"));
    public static final StreamCodec<ByteBuf, C2SSaveEntityName> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            C2SSaveEntityName::linkId,
            ByteBufCodecs.STRING_UTF8,
            C2SSaveEntityName::name,
            C2SSaveEntityName::new
        );

    public static final IPayloadHandler<C2SSaveEntityName> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            EntityLink link = data.findEntityLink(payload.linkId());
            if (link == null) return;
            String name = (payload.name() == null) ? "" : payload.name().trim();
            // 限制长度, 防止滥用
            if (name.length() > 32) name = name.substring(0, 32);
            link.setName(name);
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncEntityLinks(sp, data);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

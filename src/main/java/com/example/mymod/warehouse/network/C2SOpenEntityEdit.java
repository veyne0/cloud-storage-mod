package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家右键实体图标, 请求打开编辑界面 (改名 + 解除连接).
 * <p>
 * 服务端: 找 link → 算图标 (spawn egg) → 发 {@link S2COpenEntityEdit} 让客户端开屏.
 */
public record C2SOpenEntityEdit(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SOpenEntityEdit> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "request_open_entity_edit"));
    public static final StreamCodec<ByteBuf, C2SOpenEntityEdit> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            C2SOpenEntityEdit::linkId,
            C2SOpenEntityEdit::new
        );

    public static final IPayloadHandler<C2SOpenEntityEdit> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            EntityLink link = data.findEntityLink(payload.linkId());
            if (link == null) return;
            ItemStack icon = makeIcon(link.getEntityType());
            PacketDistributor.sendToPlayer(sp,
                new S2COpenEntityEdit(link.linkId(), link.name(), icon, link.entityTypeId()));
        });
    };

    private static ItemStack makeIcon(EntityType<?> type) {
        return com.example.mymod.warehouse.EntityIconUtil.iconFor(
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 服务端 → 客户端: 让客户端开"实体编辑"界面 (与 {@link S2COpenNameEntry} 相同的形式).
 * <p>
 * 命名规则的"实体版". 客户端收到后弹 EntityEditScreen.
 */
public record S2COpenEntityEdit(UUID linkId, String currentName, ItemStack icon, String entityTypeId)
    implements CustomPacketPayload {

    public static final Type<S2COpenEntityEdit> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_entity_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenEntityEdit> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC,
            S2COpenEntityEdit::linkId,
            ByteBufCodecs.STRING_UTF8,
            S2COpenEntityEdit::currentName,
            ItemStack.STREAM_CODEC,
            S2COpenEntityEdit::icon,
            ByteBufCodecs.STRING_UTF8,
            S2COpenEntityEdit::entityTypeId,
            S2COpenEntityEdit::new
        );

    public static final IPayloadHandler<S2COpenEntityEdit> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setPendingEntityEdit(
                payload.linkId, payload.currentName, payload.icon, payload.entityTypeId);
            // 直接调 EntityEditScreen 打开编辑界面 (与 S2COpenNameEntry 一样, 收到包就开屏).
            // 这里不需要先看 pending, 因为收容完第一次命名走的是 S2COpenEntityNameEntry
            // 路径 (与本包互斥). 右键图标 → C2SOpenEntityEdit → 这里.
            com.example.mymod.warehouse.EntityLink link = null;
            var links = com.example.mymod.warehouse.client.ClientWarehouseCache.getEntityLinks();
            for (var l : links) {
                if (l.linkId().equals(payload.linkId)) { link = l; break; }
            }
            boolean summoned = link != null && link.summoned();
            com.example.mymod.warehouse.screen.EntityEditScreen.open(
                payload.linkId, payload.currentName, payload.icon, payload.entityTypeId, summoned);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

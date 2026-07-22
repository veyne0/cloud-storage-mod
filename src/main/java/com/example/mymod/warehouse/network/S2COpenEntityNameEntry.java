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
 * 服务端 → 客户端: 实体刚收容完, 让客户端弹"命名"界面 (首次命名).
 * <p>
 * 与 {@link S2COpenNameEntry} 类似, 但 icon 是 spawn egg, 命名框的标题是
 * "命名收容的实体". 提交名后走 {@link C2SSaveEntityName}.
 */
public record S2COpenEntityNameEntry(UUID linkId, String defaultName, ItemStack icon)
    implements CustomPacketPayload {

    public static final Type<S2COpenEntityNameEntry> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_entity_name"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenEntityNameEntry> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC,
            S2COpenEntityNameEntry::linkId,
            ByteBufCodecs.STRING_UTF8,
            S2COpenEntityNameEntry::defaultName,
            ItemStack.STREAM_CODEC,
            S2COpenEntityNameEntry::icon,
            S2COpenEntityNameEntry::new
        );

    public static final IPayloadHandler<S2COpenEntityNameEntry> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setPendingEntityName(
                payload.linkId, payload.defaultName, payload.icon);
            // 直接弹"命名收容的实体"界面 (与 S2COpenNameEntry → NameEntryScreen 一致).
            com.example.mymod.warehouse.screen.EntityNameEntryScreen.open(
                payload.linkId, payload.defaultName, payload.icon);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

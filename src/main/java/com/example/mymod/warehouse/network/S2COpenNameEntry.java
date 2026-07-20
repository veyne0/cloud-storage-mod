package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 服务端 → 客户端: 让客户端弹出"命名该存储容器"的小窗.
 * <p>
 * ItemStack.STREAM_CODEC 是 {@code StreamCodec<RegistryFriendlyByteBuf, ItemStack>},
 * 所以整个 STREAM_CODEC 必须是 {@code StreamCodec<RegistryFriendlyByteBuf, ...>}.
 */
public record S2COpenNameEntry(UUID linkId, String defaultName, ItemStack iconStack, int slots)
    implements CustomPacketPayload {
    public static final Type<S2COpenNameEntry> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_name_entry"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenNameEntry> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, S2COpenNameEntry::linkId,
            ByteBufCodecs.STRING_UTF8, S2COpenNameEntry::defaultName,
            ItemStack.STREAM_CODEC, S2COpenNameEntry::iconStack,
            ByteBufCodecs.VAR_INT, S2COpenNameEntry::slots,
            S2COpenNameEntry::new
        );

    public static final IPayloadHandler<S2COpenNameEntry> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setPending(
                payload.linkId, payload.defaultName, payload.iconStack, payload.slots);
            // 用自实现的全屏独立界面 (不依赖 Cloth Config), 文字深色高对比, 按钮确定可点.
            com.example.mymod.warehouse.screen.NameEntryScreen.open(
                payload.linkId, payload.defaultName, payload.iconStack, payload.slots);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

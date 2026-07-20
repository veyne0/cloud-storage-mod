package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.LinkedContainer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端: 推送当前玩家的已连接容器列表.
 * <p>
 * 仓库菜单打开时, 服务端会发一份; 之后每当 addLink / removeLink / setName 也会再发一份.
 * 客户端只读, 不做修改.
 * <p>
 * Codec 用 {@link RegistryFriendlyByteBuf} 而不是 {@code ByteBuf} —— preview 数组里的
 * {@code ItemStack} 在 1.21.1 必须用 {@code HolderLookup.Provider} 序列化, 这个 buf
 * 自带 registryAccess(), 不用我们单独再去拿.
 */
public record S2CSyncLinkedContainers(List<LinkedContainer> links) implements CustomPacketPayload {
    public static final Type<S2CSyncLinkedContainers> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_links"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSyncLinkedContainers> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public S2CSyncLinkedContainers decode(RegistryFriendlyByteBuf buf) {
                var provider = buf.registryAccess();
                int size = ByteBufCodecs.VAR_INT.decode(buf);
                List<LinkedContainer> links = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    links.add(LinkedContainer.fromNbt(ByteBufCodecs.COMPOUND_TAG.decode(buf), provider));
                }
                return new S2CSyncLinkedContainers(links);
            }
            @Override
            public void encode(RegistryFriendlyByteBuf buf, S2CSyncLinkedContainers value) {
                var provider = buf.registryAccess();
                ByteBufCodecs.VAR_INT.encode(buf, value.links().size());
                for (LinkedContainer c : value.links()) {
                    ByteBufCodecs.COMPOUND_TAG.encode(buf, c.toNbt(provider));
                }
            }
        };

    public static final IPayloadHandler<S2CSyncLinkedContainers> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setLinks(payload.links);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

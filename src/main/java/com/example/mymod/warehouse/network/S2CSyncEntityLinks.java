package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端: 同步玩家收容的实体列表.
 * <p>
 * 类比 {@link S2CSyncLinkedContainers}, 但数据是 {@link EntityLink}.
 * <p>
 * Codec 用 {@link RegistryFriendlyByteBuf}, 因为 entity NBT 里可能含 itemStack,
 * 序列化时需要 registryAccess().
 */
public record S2CSyncEntityLinks(List<EntityLink> links) implements CustomPacketPayload {
    public static final Type<S2CSyncEntityLinks> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_entity_links"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSyncEntityLinks> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public S2CSyncEntityLinks decode(RegistryFriendlyByteBuf buf) {
                var provider = buf.registryAccess();
                int size = ByteBufCodecs.VAR_INT.decode(buf);
                List<EntityLink> links = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    links.add(EntityLink.fromNbt(ByteBufCodecs.COMPOUND_TAG.decode(buf), provider));
                }
                return new S2CSyncEntityLinks(links);
            }
            @Override
                    public void encode(RegistryFriendlyByteBuf buf, S2CSyncEntityLinks value) {
                        var provider = buf.registryAccess();
                        ByteBufCodecs.VAR_INT.encode(buf, value.links().size());
                        for (EntityLink l : value.links()) {
                            // 用精简版 toSyncNbt(): 不带 entityNbt. 完整 NBT 里有
                            // Brain.memories / Attributes.Modifiers 等, 这些东西
                            // 引用服务端的 Holder<Attribute>, 客户端用 ByteBufCodecs
                            // 编码会抛 EncoderException → 连接中断.
                            ByteBufCodecs.COMPOUND_TAG.encode(buf, l.toSyncNbt());
                        }
                    }
        };

    public static final IPayloadHandler<S2CSyncEntityLinks> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setEntityLinks(payload.links);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

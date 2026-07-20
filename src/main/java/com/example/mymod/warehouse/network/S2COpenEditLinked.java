package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 服务端 → 客户端: 让客户端弹出"编辑已连接容器"的小窗.
 * <p>
 * 把 linkId 之外的数据打包到 {@link CompoundTag} 里, 避免 composite 超过 6 字段上限.
 * 客户端拿到后从 nbt 里读 currentName, blockId, slots, dimension, x, y, z.
 */
public record S2COpenEditLinked(UUID linkId, CompoundTag data) implements CustomPacketPayload {
    public static final Type<S2COpenEditLinked> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_edit_linked"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenEditLinked> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, S2COpenEditLinked::linkId,
            ByteBufCodecs.COMPOUND_TAG, S2COpenEditLinked::data,
            S2COpenEditLinked::new
        );

    public static final IPayloadHandler<S2COpenEditLinked> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            CompoundTag d = payload.data;
            String currentName = d.getString("name");
            String blockId = d.getString("block");
            int slots = d.getInt("slots");
            String dimension = d.getString("dim");
            int x = d.getInt("x");
            int y = d.getInt("y");
            int z = d.getInt("z");
            com.example.mymod.warehouse.screen.EditLinkedScreen.open(
                payload.linkId, currentName, blockId, slots, dimension, x, y, z);
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 工具: 服务端在调用 {@code PacketDistributor.sendToPlayer} 之前, 用这个静态方法
     * 把数据打包成 CompoundTag, 再用 {@link #new} 构造 S2COpenEditLinked.
     */
    public static CompoundTag buildData(String currentName, String blockId, int slots,
                                       String dimension, int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", currentName == null ? "" : currentName);
        tag.putString("block", blockId);
        tag.putInt("slots", slots);
        tag.putString("dim", dimension);
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}

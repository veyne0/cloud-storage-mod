package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在仓库界面右键点了某个已连接容器图标, 想要打开"编辑"界面
 * (改名字 / 解除连接).
 * <p>
 * 服务端回一个 {@link S2COpenEditLinked} 让客户端开屏.
 */
public record C2SRequestEditLinked(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SRequestEditLinked> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "request_edit_linked"));
    public static final StreamCodec<FriendlyByteBuf, C2SRequestEditLinked> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC,
            C2SRequestEditLinked::linkId,
            C2SRequestEditLinked::new
        );

    public static final IPayloadHandler<C2SRequestEditLinked> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            var data = com.example.mymod.warehouse.WarehouseDataManager.get(sp);
            var link = data.findLink(payload.linkId);
            if (link == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c[云存储] 这个连接已经不存在了"));
                return;
            }
            // 把当前 link 的快照发给客户端, 客户端据此开屏.
            com.example.mymod.warehouse.network.WarehouseNetworking.sendTo(sp,
                new S2COpenEditLinked(
                    link.linkId(),
                    S2COpenEditLinked.buildData(
                        link.name(),
                        link.blockId(),
                        link.slots(),
                        link.dimension().location().toString(),
                        link.pos().getX(), link.pos().getY(), link.pos().getZ()
                    )
                ));
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

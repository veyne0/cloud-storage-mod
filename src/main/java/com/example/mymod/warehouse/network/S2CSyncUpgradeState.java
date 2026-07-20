package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端 → 客户端: 同步当前玩家的仓库升级状态.
 * <p>
 * 携带:
 * <ul>
 *   <li>{@code level}: 当前等级 (1~5)</li>
 *   <li>{@code progress}: 升到下一级时, 每种材料已交了几个. key 是 {@link Item},
 *       客户端拿 {@code BuiltInRegistries.ITEM.get(ResourceLocation)} 还原</li>
 * </ul>
 * 任何修改 (服务器扣材料完成升级 / 玩家提交材料) 后都会发一份.
 * <p>
 * Codec 用 {@code RegistryFriendlyByteBuf} 以便后续扩展 (e.g. 想要把 ItemStack 直接放进去也行).
 */
public record S2CSyncUpgradeState(int level, Map<Item, Integer> progress)
    implements CustomPacketPayload {
    public static final Type<S2CSyncUpgradeState> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_upgrade"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSyncUpgradeState> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public S2CSyncUpgradeState decode(RegistryFriendlyByteBuf buf) {
                int lvl = ByteBufCodecs.VAR_INT.decode(buf);
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                Map<Item, Integer> p = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    ResourceLocation id = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
                    Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                    int count = ByteBufCodecs.VAR_INT.decode(buf);
                    if (item != null) p.put(item, count);
                }
                return new S2CSyncUpgradeState(lvl, p);
            }
            @Override
            public void encode(RegistryFriendlyByteBuf buf, S2CSyncUpgradeState value) {
                ByteBufCodecs.VAR_INT.encode(buf, value.level());
                ByteBufCodecs.VAR_INT.encode(buf, value.progress().size());
                for (var e : value.progress().entrySet()) {
                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(e.getKey());
                    ByteBufCodecs.STRING_UTF8.encode(buf, id.toString());
                    ByteBufCodecs.VAR_INT.encode(buf, e.getValue());
                }
            }
        };

    public static final IPayloadHandler<S2CSyncUpgradeState> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.setUpgrade(
                payload.level(), payload.progress());
            // 状态先到位, 再开屏 (保证 UpgradeScreen 拿到的就是最新数据)
            if (com.example.mymod.warehouse.client.ClientWarehouseCache.requestOpenUpgrade) {
                com.example.mymod.warehouse.client.ClientWarehouseCache.requestOpenUpgrade = false;
                com.example.mymod.warehouse.screen.UpgradeScreen.open();
            }
        });
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ============== 服务端工具方法 ==============
    /** 服务端: 把当前玩家的升级状态发给指定玩家. */
    public static void sendTo(ServerPlayer sp) {
        PersonalWarehouseData data = WarehouseDataManager.get(sp);
        Map<Item, Integer> progress = new LinkedHashMap<>(data.getUpgradeProgress());
        com.example.mymod.warehouse.network.WarehouseNetworking.sendTo(
            sp, new S2CSyncUpgradeState(data.getLevel(), progress));
    }
}

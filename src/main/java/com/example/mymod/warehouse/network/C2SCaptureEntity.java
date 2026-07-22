package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家用"实体收容器"右键了一个实体, 请服务端收容.
 * <p>
 * 客户端用这个包时, 必须持有 {@code ItemStack} 的 NBT 标记 (按物品堆区分), 防止误触发.
 * 服务端:
 * <ol>
 *   <li>验证玩家手拿的是"实体收容器" + 等级上限够</li>
 *   <li>从世界里读 entity 完整 NBT (去除 UUID + 位置 + 死亡状态)</li>
 *   <li>删除原 entity</li>
 *   <li>把 NBT 存成新 {@link EntityLink}, 弹命名框</li>
 * </ol>
 * <b>黑名单</b>: 玩家, Boss (凋灵/末影龙/监守者) 不能收.
 */
public record C2SCaptureEntity(int entityId) implements CustomPacketPayload {
    public static final Type<C2SCaptureEntity> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "capture_entity"));
    public static final StreamCodec<ByteBuf, C2SCaptureEntity> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            C2SCaptureEntity::entityId,
            C2SCaptureEntity::new
        );

    public static final IPayloadHandler<C2SCaptureEntity> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!sp.getMainHandItem().is(
                com.example.mymod.ExampleMod.ENTITY_CONTAINER.get())) {
                return; // 没拿收容器, 拒绝
            }

            Entity target = sp.level().getEntity(payload.entityId());
            if (target == null) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u5b9e\u4f53\u4e0d\u5b58\u5728"));
                return;
            }

            // ---- 黑名单 ----
            if (target instanceof Player) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u4e0d\u80fd\u6536\u5bb9\u73a9\u5bb6"));
                return;
            }
            // ---- 收容出去的实体不能再被收 (PersistentData 里打了 WarehouseLinkId 标记) ----
            if (target.getPersistentData().hasUUID("WarehouseLinkId")) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7e[\u4e91\u5b58\u50a8] \u8be5\u5b9e\u4f53\u662f\u4ece\u4e91\u7aef\u53ec\u5524\u51fa\u6765\u7684, \u8bf7\u5148\u53ec\u56de\u4e91\u7aef"));
                return;
            }
            EntityType<?> type = target.getType();
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String id = typeId == null ? "" : typeId.toString();
            if (id.equals("minecraft:wither") || id.equals("minecraft:ender_dragon")
                || id.equals("minecraft:warden")) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u4e0d\u80fd\u6536\u5bb9 Boss"));
                return;
            }

            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            if (data.getEntityLinks().size() >= data.getMaxEntityLinks()) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u5b9e\u4f53\u69fd\u4f4d\u5df2\u6ee1 (Lv." + data.getLevel()
                        + " \u6700\u591a " + data.getMaxEntityLinks() + " \u4e2a). \u00a7e\u6309 V \u2192 \u5347\u7ea7"));
                return;
            }

            // ---- 读 entity 完整 NBT (除 UUID + 位置 + Motion) ----
            CompoundTag nbt = new CompoundTag();
            target.save(nbt);
            nbt.remove("UUID");
            nbt.remove("UUIDMost");
            nbt.remove("UUIDLeast");
            nbt.remove("Pos");
            nbt.remove("Motion");
            nbt.remove("OnGround");
            nbt.remove("FallDistance");
            nbt.remove("Fire");
            nbt.remove("Air");
            nbt.remove("Rotation");
            nbt.remove("invulnerable");
            nbt.remove("PortalCooldown");
            // 保证能召唤出来: Health 有值, 活着
            if (target instanceof LivingEntity le) {
                nbt.putFloat("Health", le.getMaxHealth());
            }

            // ---- 删实体 ----
            target.discard();

            // ---- 创建 EntityLink + 弹命名框 ----
            UUID linkId = UUID.randomUUID();
            String defaultName = makeDefaultName(data, type);
            EntityLink link = new EntityLink(linkId, defaultName, id, nbt, false);
            data.addEntityLink(link);
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncEntityLinks(sp, data);

            // 选 spawn egg 作为图标 (如果有), 否则用末影珍珠
            ItemStack icon = makeIcon(type);
            PacketDistributor.sendToPlayer(sp,
                new S2COpenEntityNameEntry(linkId, defaultName, icon));

            ExampleMod.LOGGER.info("[CloudStorage] Player {} captured entity '{}' ({}), nbt size={}",
                sp.getName().getString(), id, defaultName, nbt.size());
        });
    };

    private static String makeDefaultName(PersonalWarehouseData data, EntityType<?> type) {
        String base = type.getDescription().getString();
        int n = 1;
        for (var l : data.getEntityLinks()) {
            if (l.name().startsWith(base + " #")) n++;
        }
        return base + " #" + n;
    }

    private static ItemStack makeIcon(EntityType<?> type) {
        // 优先按 entity 注册名 (xxx_spawn_egg) 找刷怪蛋, 找不到了走末影珍珠.
        // 注意: 不能直接用 SpawnEggItem.getType(null) — 1.21.1 它要 HolderLookup.Provider.
        return com.example.mymod.warehouse.EntityIconUtil.iconFor(
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

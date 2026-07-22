package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在仓库 UI 点实体图标的"召唤"按钮.
 * <p>
 * 服务端:
 * <ol>
 *   <li>从 NBT 重建实体, 放在玩家脚下 (安全位置)</li>
 *   <li>在实体 PersistentData 上打 linkId 标记 (用于后续召回查找)</li>
 *   <li>设置 link.summoned = true</li>
 *   <li>同步到客户端</li>
 * </ol>
 * <p>
 * 注意: 召唤时清掉 link 自己的 UUID, 让原 NBT 里的 UUID 失效, 这样重进游戏不会
 * 出现"两个相同实体".
 */
public record C2SSummonEntity(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SSummonEntity> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "summon_entity"));
    public static final StreamCodec<ByteBuf, C2SSummonEntity> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,
            C2SSummonEntity::linkId,
            C2SSummonEntity::new
        );

    public static final IPayloadHandler<C2SSummonEntity> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PersonalWarehouseData data = WarehouseDataManager.get(sp);
            EntityLink link = data.findEntityLink(payload.linkId());
            if (link == null) return;
            if (link.summoned()) {
                // 已经在世界里, 看下还能不能找到
                if (findSummonedEntity(sp.serverLevel(), link.linkId()) != null) {
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7e[\u4e91\u5b58\u50a8] \u8be5\u5b9e\u4f53\u5df2\u5728\u4e16\u754c\u4e2d"));
                    return;
                }
                // 之前 summoned=true 但实体丢失 (e.g. 区块卸载, 服务器重启) → 当作没召唤过, 继续
            }
            summonFromLink(sp, link);
        });
    };

    /**
     * 召回: 把世界里 linkId 标记的实体存回 NBT, 删除实体, link.summoned=false.
     */
    public static void recallToLink(ServerPlayer sp, EntityLink link) {
        Entity e = findSummonedEntity(sp.serverLevel(), link.linkId());
        if (e == null) {
            sp.sendSystemMessage(Component.literal(
                "\u00a7e[\u4e91\u5b58\u50a8] \u627e\u4e0d\u5230\u5df2\u547d\u540d\u7684\u5b9e\u4f53, \u5df2\u6807\u4e3a\u5b58\u50a8\u4e2d"));
            link.setSummoned(false);
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncEntityLinks(sp, WarehouseDataManager.get(sp));
            return;
        }
        // 重新读 NBT (状态可能变了, 防止被覆盖)
        CompoundTag newNbt = new CompoundTag();
        e.save(newNbt);
        newNbt.remove("UUID");
        newNbt.remove("UUIDMost");
        newNbt.remove("UUIDLeast");
        newNbt.remove("Pos");
        newNbt.remove("Motion");
        newNbt.remove("OnGround");
        newNbt.remove("FallDistance");
        newNbt.remove("Fire");
        newNbt.remove("Air");
        newNbt.remove("Rotation");
        newNbt.remove("invulnerable");
        newNbt.remove("PortalCooldown");
        if (e instanceof LivingEntity le) {
            newNbt.putFloat("Health", Math.max(1.0f, le.getHealth()));
        }
        // 覆盖 NBT
        link.replaceNbt(newNbt);
        link.setSummoned(false);
        e.discard();
        WarehouseDataManager.setDirty();
        WarehouseDataManager.sendSyncEntityLinks(sp, WarehouseDataManager.get(sp));
        sp.sendSystemMessage(Component.literal(
            "\u00a7a[\u4e91\u5b58\u50a8] \u5df2\u53ec\u56de \"" + link.getDisplayName().getString() + "\""));
    }

    /**
     * 把 link 的 entityNbt 重建到玩家脚下. 公共方法, 供 C2SUnlinkEntity / 其他包调用.
     */
    public static void summonFromLink(ServerPlayer sp, EntityLink link) {
        ServerLevel level = sp.serverLevel();
        try {
            EntityType<?> type = link.getEntityType();
            Entity e = type.create(level);
            if (e == null) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u521b\u5efa\u5b9e\u4f53\u5931\u8d25 (entityType=" + link.entityTypeId() + ")"));
                return;
            }
            // 放在玩家脚下 + 偏移, 找安全位置 (避免卡墙)
            BlockPos spawn = findSafeSpawn(level, sp.blockPosition().above());
            e.load(link.entityNbt().copy());
            e.setUUID(UUID.randomUUID()); // 新 UUID, 避免和原存档冲突
            e.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            // 打标记: 让 recallToLink 能找到
            e.getPersistentData().putUUID("WarehouseLinkId", link.linkId());
            // 视觉标识: 召唤出来的实体默认发光 (Glowing) — 让玩家一眼能看出"这个
            // 是从云端召唤出来的, 不能再次被收容". Glowing 不影响游戏平衡, 但视觉
            // 提示很有用. 永久发光, 通过 tag 让死亡/回收时一起消失.
            e.addTag("WarehouseSummoned");
            e.setGlowingTag(true);
            level.addFreshEntity(e);
            link.setSummoned(true);
            WarehouseDataManager.setDirty();
            WarehouseDataManager.sendSyncEntityLinks(sp, WarehouseDataManager.get(sp));
            sp.sendSystemMessage(Component.literal(
                "\u00a7a[\u4e91\u5b58\u50a8] \u5df2\u53ec\u5524 \"" + link.getDisplayName().getString() + "\""));
        } catch (Throwable t) {
            ExampleMod.LOGGER.error("[CloudStorage] Failed to summon entity from link {}", link.linkId(), t);
            sp.sendSystemMessage(Component.literal(
                "\u00a7c[\u4e91\u5b58\u50a8] \u53ec\u5524\u5b9e\u4f53\u51fa\u9519: " + t.getMessage()));
        }
    }

    /**
     * 找一个安全的 spawn 位置: 给定位置 + 0~3 高度, 找一个头顶有空气的脚下方块.
     */
    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos base) {
        for (int dy = 0; dy < 4; dy++) {
            BlockPos p = base.above(dy);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid()) {
                return p;
            }
        }
        return base; // fallback
    }

    /**
     * 在世界里找 PersistentData 里有 {@code WarehouseLinkId} == targetLinkId 的实体.
     */
    public static Entity findSummonedEntity(ServerLevel level, UUID targetLinkId) {
        for (Entity e : level.getAllEntities()) {
            if (e.getPersistentData().hasUUID("WarehouseLinkId")
                && e.getPersistentData().getUUID("WarehouseLinkId").equals(targetLinkId)) {
                return e;
            }
        }
        return null;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

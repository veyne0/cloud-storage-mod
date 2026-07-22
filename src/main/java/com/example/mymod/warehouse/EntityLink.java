package com.example.mymod.warehouse;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一条 "云存储收容的实体" 记录.
 * <p>
 * 玩家手持"实体收容器"右键世界中的实体 → 服务端把该实体的完整 NBT 存到本类, 然后
 * 移除原实体, 后续可以在仓库 UI 右侧的"实体"图标区点击召唤/召回.
 * <p>
 * 关键字段:
 * <ul>
 *   <li>{@link #linkId} - 唯一标识, 重命名/解除连接按这个找</li>
 *   <li>{@link #name} - 玩家填的名字 (可空, 显示时 fallback 到实体类型名)</li>
 *   <li>{@link #entityTypeId} - 实体类型 id (例如 "minecraft:cow"), 用于 UI 图标 + 召唤时重建</li>
 *   <li>{@link #entityNbt} - 实体的完整 NBT (除 UUID + 位置), 召唤时回填</li>
 *   <li>{@link #summoned} - 是否正在世界中 (true: 已召唤, 实体在世界里, 不能重复召唤;
 *       false: 存储在云端). 注意: 玩家退出游戏 / 区块卸载, 实体可能丢失, 召唤时如果
 *       找不到会自动从 NBT 重新生成</li>
 * </ul>
 * 容量上限见 {@link #maxEntityLinksFor(int)}.
 */
public class EntityLink {
    private final UUID linkId;
    private String name;
    private final String entityTypeId; // 例如 "minecraft:cow"
    /** 完整 entity NBT (除 UUID + Pos). */
    private CompoundTag entityNbt;
    /** 是否已召唤到世界里. true 表示"已召唤, 不要再 spawn"; false 表示在云端. */
    private boolean summoned;

    public EntityLink(UUID linkId, String name, String entityTypeId, CompoundTag entityNbt, boolean summoned) {
        this.linkId = linkId;
        this.name = name;
        this.entityTypeId = entityTypeId;
        this.entityNbt = entityNbt == null ? new CompoundTag() : entityNbt;
        this.summoned = summoned;
    }

    public UUID linkId() { return linkId; }
    public String name() { return name; }
    public String entityTypeId() { return entityTypeId; }
    public CompoundTag entityNbt() { return entityNbt; }
    public boolean summoned() { return summoned; }

    public void setName(String name) { this.name = name; }
    public void setSummoned(boolean v) { this.summoned = v; }
    /** 替换 NBT (e.g. 召回时把当前实体的 NBT 写回). */
    public void replaceNbt(CompoundTag nbt) {
        this.entityNbt = (nbt == null) ? new CompoundTag() : nbt;
    }

    /**
     * 拿到实体类型 (UI / 召唤时都用得到). 拿不到时返回 {@link EntityType#PIG} 作 fallback.
     */
    public EntityType<?> getEntityType() {
        try {
            ResourceLocation id = ResourceLocation.parse(entityTypeId);
            return BuiltInRegistries.ENTITY_TYPE.get(id);
        } catch (Throwable t) {
            return EntityType.PIG;
        }
    }

    /** 显示名: 玩家填了名字就用名字, 否则用实体类型名. */
    public Component getDisplayName() {
        if (name != null && !name.isEmpty()) return Component.literal(name);
        EntityType<?> t = getEntityType();
        return Component.literal(t.getDescription().getString());
    }

    /**
     * 鼠标悬浮图标时显示的 tooltip: 名字 + 状态 + 实体类型 id.
     */
    public List<Component> toTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(getDisplayName());
        lines.add(Component.literal(summoned ? "§a已召唤" : "§7存储中"));
        lines.add(Component.literal("§8" + entityTypeId));
        return lines;
    }

    public CompoundTag toNbt(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", linkId);
        tag.putString("name", name == null ? "" : name);
        tag.putString("type", entityTypeId);
        tag.putBoolean("summoned", summoned);
        tag.put("nbt", entityNbt.copy());
        return tag;
    }

    /**
     * 给客户端同步用的精简版 NBT: 不带 {@code entityNbt}.
     * <p>
     * 为啥要省: 完整 NBT 里包含 {@code Brain.memories} / {@code Attributes.Modifiers} /
     * {@code ActiveEffects} 等子结构, 这些东西的 value holder 引用的是 {@code Holder<Attribute>},
     * 是服务端注册表里的对象. 客户端通过 {@code ByteBufCodecs.COMPOUND_TAG} 编码时
     * <strong>registry context 不对</strong>, 序列化到 {@code ResourceLocation} 这一步
     * 就抛 {@code EncoderException: Failed to encode packet 'clientbound/minecraft:custom_payload'},
     * 服务端收到编码失败立刻 {@code channel.close()}, 玩家被踢出服务器 ("连接已丢失").
     * <p>
     * 客户端拿到精简 NBT 后, 仓库 UI 仍然能正常画图标 (从 {@code type} 找刷怪蛋)
     * 和显示状态 ({@code summoned} 决定边框颜色). {@code entityNbt} 仅服务端在
     * {@code C2SSummonEntity.summonFromLink} 时使用.
     */
    public CompoundTag toSyncNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", linkId);
        tag.putString("name", name == null ? "" : name);
        tag.putString("type", entityTypeId);
        tag.putBoolean("summoned", summoned);
        // 故意不写 "nbt" key. 客户端遇到缺失就当作空, 不影响 UI.
        return tag;
    }

    public static EntityLink fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        // 兼容精简版: 如果没有 "nbt" key (新版 S2CSyncEntityLinks 同步包不写), 给个空 NBT
        CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : new CompoundTag();
        return new EntityLink(
            tag.getUUID("id"),
            tag.getString("name"),
            tag.getString("type"),
            nbt,
            tag.getBoolean("summoned")
        );
    }

    // ==================== 容量表 ====================

    /**
     * 给定等级可存储的实体数上限.
     * <pre>
     *   Lv1: 2,  Lv2: 4,  Lv3: 6,  Lv4: 8,  Lv5: 10
     * </pre>
     * 与已连接容器数上限独立, 互不影响.
     */
    public static int maxEntityLinksFor(int level) {
        return switch (level) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 6;
            case 4 -> 8;
            case 5 -> 10;
            default -> 2;
        };
    }
}

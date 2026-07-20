package com.example.mymod.warehouse;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的云存储数据: 等级 + 仓库本体 (ItemStackHandler) + 已连接的容器列表 + 升级进度.
 * <p>
 * 等级决定两个权益:
 * <ul>
 *   <li>已连接容器数上限 (5/10/20/50/100)</li>
 *   <li>仓库本体页数 (1/5/20/50/100), 每页 54 格, 5 页 = 270 格</li>
 * </ul>
 * 升级采用"分步交"模式: 玩家可以一次交一部分材料, 服务端把已交数
 * 记在 {@link #upgradeProgress}; 当所有材料都达到目标数, 自动升级 + 清空进度.
 * 玩家跨等级升级 (例如 2→3) 时, 进度里只记 2→3 这一级需要的材料.
 */
public class PersonalWarehouseData {
    public static final int SLOTS_PER_PAGE = 54; // 与大箱子 (双箱) 一致: 9x6
    public static final int DEFAULT_LEVEL = 1;

    private int level; // 1~5
    private final ItemStackHandler storage;
    private final List<LinkedContainer> linkedContainers = new ArrayList<>();
    /** 升级进度: 升到 {@code level+1} 需要的每种材料当前交了几个. LinkedHashMap 保序. */
    private final Map<Item, Integer> upgradeProgress = new LinkedHashMap<>();

    public PersonalWarehouseData() {
        this(DEFAULT_LEVEL);
    }

    public PersonalWarehouseData(int level) {
        this.level = Math.max(1, Math.min(5, level));
        // 用 DirtyTrackingItemStackHandler, 任何槽位变更自动标脏 + 落盘.
        this.storage = new DirtyTrackingItemStackHandler(WarehouseLevel.pagesFor(this.level) * SLOTS_PER_PAGE);
    }

    // ============== 等级 ==============

    public int getLevel() { return level; }
    public WarehouseLevel getLevelDef() { return WarehouseLevel.of(level); }

    /**
     * 应用新等级. 只在服务端改; 改完后调 {@link WarehouseDataManager#setDirty()}.
     * 同时扩容/缩容 storage 到新等级对应的页数.
     * <p>
     * <b>重要</b>: 1.21.1 的 {@code ItemStackHandler.setSize(int)} 实现是
     * <pre>stacks = NonNullList.withSize(size, ItemStack.EMPTY);</pre>
     * — 直接重建一个空的 {@code NonNullList}, <b>所有旧物品全部丢失</b>.
     * 所以这里必须手动备份前 n 个 slot 的内容, 扩容后再还原回去.
     */
    public void setLevel(int newLevel) {
        newLevel = Math.max(1, Math.min(5, newLevel));
        if (newLevel == level) return;
        int newPages = WarehouseLevel.pagesFor(newLevel);
        int newSize = newPages * SLOTS_PER_PAGE;
        int oldSize = storage.getSlots();
        int backupSize = Math.min(oldSize, newSize);
        // 1. 备份前 n 个 slot 的物品 (n = min(oldSize, newSize))
        ItemStack[] backup = new ItemStack[backupSize];
        for (int i = 0; i < backupSize; i++) {
            backup[i] = storage.getStackInSlot(i).copy();
        }
        // 2. 扩容 (这步会清空所有 item)
        storage.setSize(newSize);
        // 3. 还原
        for (int i = 0; i < backupSize; i++) {
            if (!backup[i].isEmpty()) {
                storage.setStackInSlot(i, backup[i]);
            }
        }
        level = newLevel;
    }

    /** 当前等级的"最大可连接容器数". */
    public int getMaxLinkedContainers() {
        return WarehouseLevel.maxContainersFor(level);
    }

    /** 当前等级的"总页数". */
    public int getPageCount() {
        return WarehouseLevel.pagesFor(level);
    }

    public int getTotalSlots() {
        return getPageCount() * SLOTS_PER_PAGE;
    }

    public ItemStackHandler getStorage() { return storage; }
    public List<LinkedContainer> getLinkedContainers() { return linkedContainers; }

    // ============== 升级进度 ==============

    /** 当前等级对应的"下一级需要的材料"已交了多少. 只读视图. */
    public Map<Item, Integer> getUpgradeProgress() {
        return Collections.unmodifiableMap(upgradeProgress);
    }

    /**
     * 把进度对齐到 {@code def.requirements} 的 key 集合 (漏交的不算, 多余的丢弃).
     * 一般在 {@code setLevel} 之后或 {@link WarehouseLevel} 改了之后调用.
     */
    private void syncUpgradeProgressKeys() {
        Map<Item, Integer> expected = getLevelDef().next() != null
            ? new LinkedHashMap<>(getLevelDef().next().requirements)
            : Collections.emptyMap();
        // 保留已有进度, 移除不在目标 key 集合里的项
        var it = upgradeProgress.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!expected.containsKey(e.getKey())) it.remove();
        }
        // 缺失的 key 补 0
        for (Item k : expected.keySet()) {
            upgradeProgress.putIfAbsent(k, 0);
        }
    }

    /**
     * 提交升级材料 (从玩家背包扣). 自动按"已交 / 目标"逐项扣; 扣到上限就跳过.
     * 返回是否所有材料都达标 (true 表示可以升级了, 由调用方调 {@link #setLevel}).
     *
     * @param takeFrom 玩家背包 (实际扣物品的来源)
     */
    public boolean submitUpgradeMaterials(net.minecraft.world.entity.player.Inventory takeFrom) {
        WarehouseLevel def = getLevelDef();
        WarehouseLevel next = def.next();
        if (next == null) return false; // 已满级
        syncUpgradeProgressKeys();
        boolean allDone = true;
        for (var entry : next.requirements.entrySet()) {
            Item item = entry.getKey();
            int target = entry.getValue();
            int have = upgradeProgress.getOrDefault(item, 0);
            int need = target - have;
            if (need <= 0) continue;
            int took = takeItemsFromInventory(takeFrom, item, need);
            int newHave = have + took;
            upgradeProgress.put(item, newHave);
            if (newHave < target) allDone = false;
        }
        return allDone;
    }

    /** 从玩家背包扣指定物品 (任意 meta 都行, 不查 NBT). 返回实际扣的数量. */
    private static int takeItemsFromInventory(net.minecraft.world.entity.player.Inventory inv, Item item, int want) {
        int remaining = want;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            if (s.getCount() <= 0) inv.setItem(i, ItemStack.EMPTY);
            remaining -= take;
        }
        return want - remaining;
    }

    /** 升级完成, 清空进度, 升到下一级. 由调用方在 {@link #submitUpgradeMaterials} 返回 true 时调用. */
    public void completeUpgrade() {
        WarehouseLevel next = getLevelDef().next();
        if (next == null) return;
        upgradeProgress.clear();
        setLevel(next.level);
    }

    // ============== 已连接容器管理 ==============

    public LinkedContainer addLink(LinkedContainer link) {
        linkedContainers.add(link);
        return link;
    }

    public boolean removeLink(UUID linkId) {
        return linkedContainers.removeIf(c -> c.linkId().equals(linkId));
    }

    public LinkedContainer findLink(UUID linkId) {
        for (LinkedContainer c : linkedContainers) {
            if (c.linkId().equals(linkId)) return c;
        }
        return null;
    }

    /**
     * 按 (维度, 位置) 找已连接的容器 —— 用于 "防止同一个箱子被连接两次".
     */
    public LinkedContainer findLinkByLocation(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                                               net.minecraft.core.BlockPos pos) {
        for (LinkedContainer c : linkedContainers) {
            if (c.dimension() == dim && c.pos().equals(pos)) return c;
        }
        return null;
    }

    // ============== NBT 序列化 ==============

    /** 1.21.1 的 IItemHandler#serializeNBT 需要 HolderLookup.Provider. */
    public CompoundTag toNbt(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", level);
        tag.put("storage", storage.serializeNBT(provider));
        ListTag list = new ListTag();
        for (LinkedContainer c : linkedContainers) {
            list.add(c.toNbt(provider));
        }
        tag.put("links", list);
        // 升级进度: Map<Item, Integer> → 序列化成一个 ListTag<{id, count}>
        if (!upgradeProgress.isEmpty()) {
            ListTag p = new ListTag();
            for (var e : upgradeProgress.entrySet()) {
                CompoundTag et = new CompoundTag();
                et.putString("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
                et.putInt("count", e.getValue());
                p.add(et);
            }
            tag.put("upgradeProgress", p);
        }
        return tag;
    }

    public static PersonalWarehouseData fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        int level = tag.contains("level") ? tag.getInt("level") : DEFAULT_LEVEL;
        PersonalWarehouseData d = new PersonalWarehouseData(level);
        if (tag.contains("storage")) {
            d.storage.deserializeNBT(provider, tag.getCompound("storage"));
        }
        if (tag.contains("links")) {
            ListTag list = tag.getList("links", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                d.linkedContainers.add(LinkedContainer.fromNbt(list.getCompound(i), provider));
            }
        }
        if (tag.contains("upgradeProgress")) {
            ListTag p = tag.getList("upgradeProgress", Tag.TAG_COMPOUND);
            for (int i = 0; i < p.size(); i++) {
                CompoundTag et = p.getCompound(i);
                ResourceLocation id = ResourceLocation.parse(et.getString("id"));
                Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (item == null) continue;
                d.upgradeProgress.put(item, et.getInt("count"));
            }
            d.syncUpgradeProgressKeys();
        }
        return d;
    }
}

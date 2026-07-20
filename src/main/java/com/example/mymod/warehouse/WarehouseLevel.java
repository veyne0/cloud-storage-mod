package com.example.mymod.warehouse;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仓库等级表 —— 等级 1~5.
 * <p>
 * 每级两个权益:
 * <ul>
 *   <li>{@link #maxLinkedContainers} - 可连接的世界容器数量上限</li>
 *   <li>{@link #warehousePages} - 仓库本体格数 (每页 54 格, 5 页 = 270 格)</li>
 * </ul>
 * 升级到下一级需要的材料放在 {@link #requirements}, 任意同 ID 的 ItemStack 都算 (不查 NBT).
 *
 * <p>升级规则 (来自用户):
 * <pre>
 *   1 -> 2:  4 个箱子, 10 个铁
 *   2 -> 3: 15 个箱子, 32 个铁
 *   3 -> 4: 30 个箱子, 64 个铁, 32 个金锭
 *   4 -> 5:100 个箱子, 32 个金锭, 10 个钻石
 * </pre>
 */
public enum WarehouseLevel {
    L1(1,   5,   1, null),
    L2(2,  10,   5, make(Items.CHEST, 4, Items.IRON_INGOT, 10)),
    L3(3,  20,  20, make(Items.CHEST, 15, Items.IRON_INGOT, 32)),
    L4(4,  50,  50, make(Items.CHEST, 30, Items.IRON_INGOT, 64, Items.GOLD_INGOT, 32)),
    L5(5, 100, 100, make(Items.CHEST, 100, Items.GOLD_INGOT, 32, Items.DIAMOND, 10));

    public final int level;
    public final int maxLinkedContainers;
    public final int warehousePages;
    /** 升到这一级需要的材料. null 表示无 (已是最高级). LinkedHashMap 保证 UI 顺序. */
    public final Map<Item, Integer> requirements;

    WarehouseLevel(int level, int maxLinkedContainers, int warehousePages,
                   Map<Item, Integer> requirements) {
        this.level = level;
        this.maxLinkedContainers = maxLinkedContainers;
        this.warehousePages = warehousePages;
        this.requirements = requirements;
    }

    public static WarehouseLevel of(int level) {
        for (WarehouseLevel l : values()) {
            if (l.level == level) return l;
        }
        return L1;
    }

    /** 下一级, 已是最高级返回 null. */
    public WarehouseLevel next() {
        return of(level + 1);
    }

    public boolean isMax() {
        return level == L5.level;
    }

    /**
     * 构造不可变的 LinkedHashMap: {@code (Item, count, Item, count, ...)}.
     * enum 常量里直接调用, {@link Items} 在那一刻已经初始化好.
     */
    private static Map<Item, Integer> make(Object... pairs) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((Item) pairs[i], (Integer) pairs[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    /** 静态工具: 给定等级对应的最大已连接容器数. 越界 (level<1) 视为 1. */
    public static int maxContainersFor(int level) {
        return of(level).maxLinkedContainers;
    }

    /** 静态工具: 给定等级对应的仓库页数. */
    public static int pagesFor(int level) {
        return of(level).warehousePages;
    }
}

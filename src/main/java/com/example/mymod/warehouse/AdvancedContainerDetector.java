package com.example.mymod.warehouse;

import java.util.Set;

/**
 * 判断一个方块是否属于"高级 GUI 容器" — 远程开屏时容易在客户端 IllegalStateException
 * {@code "Client could not locate tile"} 崩的.
 * <p>
 * 检测方式: 看方块注册 id 的命名空间 (mod id). 已知会出问题的 mod 都加进
 * {@link #ADVANCED_NAMESPACES}. 玩家也可以在编辑界面手动把某个容器标记成"高级".
 */
public final class AdvancedContainerDetector {
    private AdvancedContainerDetector() {}

    /**
     * 已知"高级 GUI 容器"所属 mod 的命名空间.
     * <p>
     * 经验来源: 用户报告 + 实际测试 — 这些 mod 的机器方块在远距离 (~180 格) 客户端
     * 收到 openScreen 包时会做 tile lookup, 找不到真实 BE 就抛异常, 整个客户端断线.
     * 命名空间大小写敏感, 与 {@code ResourceLocation} 一致.
     */
    public static final Set<String> ADVANCED_NAMESPACES = Set.of(
        // Mekanism 全家
        "mekanism",            // 主 mod
        "mekanismgenerators",  // 风力/热力/太阳能发电机
        "mekanismadditions",   // 加速器等
        "mekanismtools",       // 工具相关
        "mekanismmatter",      // 物质发生器
        // Create 全家
        "create",              // 动力锯/粉碎机/搅拌机/压榨机/动力炉等
        // Thermal
        "thermal",             // 热力系列机器
        // Immersive Engineering
        "immersiveengineering", // 各种工程师机器
        // Tinkers' Construct
        "tconstruct",          // 冶炼炉等
        "tmechworks",          // 拓展
        // Ender IO
        "enderio",             // 各种机器
        // Applied Energistics 2
        "appliedenergistics2", // ME 机器
        // Botania
        "botania",             // 一些带复杂 GUI 的方块
        // Refined Storage / AE 相关
        "refinedstorage",
        "extrastorage",
        // Sophisticated Storage
        "sophisticatedstorage", // 各种定制箱子, BE 类型严格, 远距离 push 易触发
        // 万能的工业 2
        "industrialforegoing"
    );

    /**
     * 给定 blockId (例如 {@code "mekanismgenerators:wind_generator"}), 判断是否高级容器.
     *
     * @param blockId 方块注册 id, 可以为 null
     * @return true = 已知/疑似会崩, 应当限制 175 格
     */
    public static boolean isAdvanced(String blockId) {
        if (blockId == null || blockId.isEmpty()) return false;
        int colon = blockId.indexOf(':');
        if (colon < 0) return false;
        String namespace = blockId.substring(0, colon);
        return ADVANCED_NAMESPACES.contains(namespace);
    }
}

package com.example.mymod.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一条 "已连接到云存储" 的世界存储容器记录.
 * <p>
 * 玩家在世界里对箱子/桶/潜影盒等容器使用"仓库连接器"后, 服务端把容器的位置/维度/容量/名字存到
 * {@link PersonalWarehouseData#linkedContainers} 里, 客户端再通过同步包在仓库 UI 右侧画一个图标.
 *
 * <p>用 {@link #linkId} 作为唯一标识: 玩家在多个位置连接多个容器时, 重命名 / 解开链接都按这个 id 找.
 * 不用 BlockPos 当 id 是因为同一个位置可能被换成不同的容器 (例如先箱子后被拆成漏斗), 用 UUID 更稳.
 */
public class LinkedContainer {
    /** 预览槽数: 第一页 3x3 的物品快照, 鼠标悬停图标时显示. */
    public static final int PREVIEW_SIZE = 9;

    private final UUID linkId;
    private String name;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private final int slots; // 容量, 创建时缓存; 即使后面方块被替换, 也不影响显示
    private final String blockId; // 用于渲染图标 (例如 "minecraft:chest"), 客户端拿到这个就能画贴图
    /**
     * 该容器最远能在多少格外被打开.
     * <p>
     * 出现原因: 一些 mod 容器 (Mekanism 发电机, Create 各种动力机械) 客户端在极远距离
     * (~180+ 格) 收到 openScreen 时, 客户端的 {@code ClientPacketListener.handleOpenScreen}
     * 会调 {@code level.getBlockEntity(pos)}, 拿不到真实的 BE 就抛
     * {@code IllegalStateException: Client could not locate tile}, 整个客户端断线 (显示
     * "连接已丢失"). LevelMixin 提供的 stub BE 对原版有效, 但对 mod BE 的类型校验不够,
     * 仍然会炸.
     * <p>
     * 解决: 服务端在 {@code C2SOpenLinkedContainer} 里先量玩家距离, 超过这个阈值就拒绝
     * 并提示"距离过远", 根本不让客户端尝试打开.
     * <p>
     * 默认值: 由 {@link #advanced} 决定 — 高级容器 = {@link #DEFAULT_ADVANCED_DISTANCE}
     * (175), 普通容器 = {@link #UNLIMITED_DISTANCE} (0, 不限制). 玩家在
     * 编辑界面可以手动覆盖. 玩家成功在更大距离打开过的话, maxSafeDistance 会自动
     * 上调 (单调递增, 永不下降).
     */
    private int maxSafeDistance;
    /**
     * 是否为"高级容器" — 远距离开屏有 {@code "Client could not locate tile"} 崩溃风险.
     * <p>
     * 创建时由 {@link AdvancedContainerDetector#isAdvanced(String)} 根据 blockId 命名空间
     * 推断. 玩家在编辑界面 (右键图标 → 编辑) 可以手动切换. 决定 maxSafeDistance 的默认值.
     */
    private boolean advanced;
    /** 服务端最近一次发的第一页前 PREVIEW_SIZE 个物品快照 (用于 tooltip). 客户端/服务端都填. */
    private ItemStack[] preview = new ItemStack[PREVIEW_SIZE];

    /** 高级容器的默认安全距离 (格). 略低于实测会崩的 180. */
    public static final int DEFAULT_ADVANCED_DISTANCE = 175;
    /** 0 = 不限制距离. 普通容器走这个值. */
    public static final int UNLIMITED_DISTANCE = 0;

    /**
     * 默认构造: 根据 blockId 自动判断是否高级容器, 决定 maxSafeDistance.
     * <p>
     * - 高级容器 (blockId 在 {@link AdvancedContainerDetector#ADVANCED_NAMESPACES} 里):
     *   maxSafeDistance = {@link #DEFAULT_ADVANCED_DISTANCE} (175)
     * - 普通容器 (原版箱子/熔炉 等): maxSafeDistance = {@link #UNLIMITED_DISTANCE} (0)
     */
    public LinkedContainer(UUID linkId, String name, ResourceKey<Level> dimension, BlockPos pos,
                           int slots, String blockId) {
        this(linkId, name, dimension, pos, slots, blockId,
             AdvancedContainerDetector.isAdvanced(blockId));
    }

    /**
     * 显式指定 advanced 标记的构造. 主要给编辑界面"切换高级/普通"时用.
     */
    public LinkedContainer(UUID linkId, String name, ResourceKey<Level> dimension, BlockPos pos,
                           int slots, String blockId, boolean advanced) {
        this.linkId = linkId;
        this.name = name;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.slots = slots;
        this.blockId = blockId;
        this.advanced = advanced;
        // 根据 advanced 设默认 maxSafeDistance.
        // - 高级 → 175
        // - 普通 → 0 (不限)
        // 玩家成功在更远距离打开过的话, recordSuccessfulOpen 会自动上调.
        this.maxSafeDistance = advanced ? DEFAULT_ADVANCED_DISTANCE : UNLIMITED_DISTANCE;
        for (int i = 0; i < PREVIEW_SIZE; i++) preview[i] = ItemStack.EMPTY;
    }

    /**
     * 全参数构造. 主要给 fromNbt 读存档用 — 直接还原 maxSafeDistance.
     */
    public LinkedContainer(UUID linkId, String name, ResourceKey<Level> dimension, BlockPos pos,
                           int slots, String blockId, boolean advanced, int maxSafeDistance) {
        this.linkId = linkId;
        this.name = name;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.slots = slots;
        this.blockId = blockId;
        this.advanced = advanced;
        this.maxSafeDistance = Math.max(0, maxSafeDistance);
        for (int i = 0; i < PREVIEW_SIZE; i++) preview[i] = ItemStack.EMPTY;
    }

    public UUID linkId() { return linkId; }
    public String name() { return name; }
    public ResourceKey<Level> dimension() { return dimension; }
    public BlockPos pos() { return pos; }
    public int slots() { return slots; }
    public String blockId() { return blockId; }
    public int maxSafeDistance() { return maxSafeDistance; }
    public boolean isAdvanced() { return advanced; }

    public void setName(String name) { this.name = name; }

    /**
     * 切换"高级容器"标记. 改完会重置 maxSafeDistance 到对应默认值.
     * 玩家在编辑界面点切换时调用.
     */
    public void setAdvanced(boolean advanced) {
        this.advanced = advanced;
        if (advanced && this.maxSafeDistance == 0) {
            this.maxSafeDistance = DEFAULT_ADVANCED_DISTANCE;
        } else if (!advanced) {
            this.maxSafeDistance = UNLIMITED_DISTANCE;
        }
    }

    /**
     * 更新最远安全距离 — 只在玩家成功在更远距离打开过该容器时被调用.
     * <p>
     * 单调递增: 永远只升不降 (防止新距离变小后下次又被卡).
     * <p>
     * <b>非高级容器 (advanced=false) 不动 maxSafeDistance</b> — 它们的策略就是无距离限制,
     * 成功打开也不应该把这个值从 0 提到非 0, 否则下次会被这个阈值拦住. 玩家手动
     * 调过 maxSafeDistance (通过编辑器) 也不会受影响, 因为 setAdvanced 不动非 0 值.
     */
    public void recordSuccessfulOpen(int distance) {
        if (!advanced) return; // 普通容器永远不限
        if (distance > this.maxSafeDistance) {
            this.maxSafeDistance = distance;
        }
    }

    public ItemStack getPreview(int i) { return preview[i]; }
    public void setPreview(int i, ItemStack stack) {
        preview[i] = (stack == null) ? ItemStack.EMPTY : stack;
    }
    /** 整组设置 preview 数组, 内部防御性 copy. */
    public void setPreviewArray(ItemStack[] items) {
        for (int i = 0; i < PREVIEW_SIZE; i++) {
            this.preview[i] = (items != null && i < items.length && items[i] != null)
                ? items[i] : ItemStack.EMPTY;
        }
    }

    /**
     * 鼠标悬浮在仓库 UI 右侧图标上时显示的 tooltip: 玩家填的名字 + 容量 + 第一页前 9 格物品预览.
     * <p>
     * 物品预览按槽位顺序排列, 每行 "物品名 x数量" 或 "物品名". 空容器显示 "(空)".
     * <p>
     * 高级容器会显示红色提示行 "[高级容器 — 需在 X 格内打开]". 当前距离由
     * {@link #appendDistanceInfo(List, int)} 在客户端拼接, 服务器构造的 tooltip
     * 没有距离信息 (因为服务器没有调用者的位置).
     */
    public List<net.minecraft.network.chat.Component> toTooltip() {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        lines.add(net.minecraft.network.chat.Component.literal(name));
        // 高级容器: 红字标识, 让玩家一眼看出"这个容器有距离限制"
        if (advanced) {
            lines.add(net.minecraft.network.chat.Component.literal(
                "§c§l[高级容器 — 需在 " + maxSafeDistance + " 格内打开]"));
        }
        // slots == 0 表示"无法用 ItemHandler 拿到容量" (如 Mekanism 机器等)
        // 显示 "?" 而不是 "0 格"
        if (slots > 0) {
            lines.add(net.minecraft.network.chat.Component.literal("§7" + slots + " 格"));
        } else {
            lines.add(net.minecraft.network.chat.Component.literal("§7特殊容器 (无 ItemHandler)"));
        }

        int nonEmpty = 0;
        for (int i = 0; i < PREVIEW_SIZE; i++) {
            ItemStack s = preview[i];
            if (s != null && !s.isEmpty()) {
                String itemName = s.getHoverName().getString();
                int count = s.getCount();
                String line = (count > 1)
                    ? "§f  " + itemName + " §7x" + count
                    : "§f  " + itemName;
                lines.add(net.minecraft.network.chat.Component.literal(line));
                nonEmpty++;
            }
        }
        if (nonEmpty == 0) {
            // 区分两种空状态:
            //  - slots > 0 但没东西: "(空)" — 玩家至少有 1 格, 只是恰好没放东西 (e.g. 风力发电机充能槽)
            //  - slots == 0: "(不支持预览)" — 这类容器自己就不暴露 ItemHandler (Mekanism 大部分机器)
            String emptyText = (slots > 0) ? "§7  (空)" : "§7  (不支持预览 — mod 未暴露 ItemHandler)";
            lines.add(net.minecraft.network.chat.Component.literal(emptyText));
        }
        return lines;
    }

    /**
     * 客户端在悬浮 tooltip 时调用: 追加实时距离信息.
     * <p>
     * 判断标准必须跟 {@code WarehouseScreen.renderContainerIcons} 里的图标判断 <b>完全一致</b>:
     * 用 {@link #maxSafeDistance} 是否 &gt; 0 决定是否有距离限制, 而不是用 {@link #advanced} 标记.
     * <p>
     * 原因: 之前用 {@code if (advanced)} 会跟图标不一致 — 图标用 {@code maxSafeDistance==0||dist<=maxSafeDistance}
     * 判断, tooltip 用 {@code advanced} 判断. 当 {@code advanced=false && maxSafeDistance>0} 时
     * (虽然正常代码路径下不会出现, 但保险起见), 图标会显示 ✘ (受距离限制), tooltip 却显示
     * "无距离限制 ✔", 玩家会困惑 "图标说不能开, 信息里说可以开". 现在统一用 maxSafeDistance,
     * 保证图标和 tooltip 永远一致.
     *
     * @param lines tooltip 行, 会在末尾追加
     * @param dist  玩家当前位置到容器中心的距离 (格, 已 sqrt 过的整数)
     */
    public void appendDistanceInfo(List<net.minecraft.network.chat.Component> lines, int dist) {
        if (maxSafeDistance > 0) {
            // 有距离限制: 显示当前距离 + 上限 + 是否在范围内
            boolean inRange = dist <= maxSafeDistance;
            String rangeMark = inRange ? "§a✔" : "§c✘";
            // 高级容器额外加个 "(高级容器)" 标识, 让玩家知道为啥这个容器有距离限制
            String tag = advanced ? " §7(高级容器)" : "";
            lines.add(net.minecraft.network.chat.Component.literal(
                "§7当前距离: " + dist + " 格 / 上限 " + maxSafeDistance + " 格 " + rangeMark + tag));
        } else {
            // 无距离限制 (maxSafeDistance == 0)
            lines.add(net.minecraft.network.chat.Component.literal(
                "§7当前距离: " + dist + " 格 §a✔ §7(无距离限制)"));
        }
    }

    public CompoundTag toNbt(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", linkId);
        tag.putString("name", name);
        tag.putString("dim", dimension.location().toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putInt("slots", slots);
        tag.putString("block", blockId);
        // "高级容器" 标记. 旧存档没这个 key, fromNbt 会回退到从 blockId 重新判断.
        tag.putBoolean("advanced", advanced);
        // 最远安全距离. 0 表示不限制. 玩家打开时单调递增.
        tag.putInt("max_safe_dist", maxSafeDistance);
        // preview 数组存成 ListTag<ItemStack>
        ListTag plist = new ListTag();
        for (int i = 0; i < PREVIEW_SIZE; i++) {
            CompoundTag it = new CompoundTag();
            ItemStack s = preview[i];
            if (s != null && !s.isEmpty()) {
                s.save(provider, it);
            }
            plist.add(it);
        }
        tag.put("preview", plist);
        return tag;
    }

    public static LinkedContainer fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        String blockId = tag.getString("block");
        boolean advanced;
        if (tag.contains("advanced")) {
            // 新存档: 读玩家设置的 advanced 标记
            advanced = tag.getBoolean("advanced");
        } else {
            // 旧存档: 根据 blockId 重新判断 (回退到自动检测)
            advanced = AdvancedContainerDetector.isAdvanced(blockId);
        }
        int maxSafe = tag.contains("max_safe_dist") ? tag.getInt("max_safe_dist") : 0;
        LinkedContainer c = new LinkedContainer(
            tag.getUUID("id"),
            tag.getString("name"),
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(tag.getString("dim"))),
            new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
            tag.getInt("slots"),
            blockId,
            advanced,
            maxSafe
        );
        // 读 preview (新版本字段, 旧存档里没有 → 默认 9 个 EMPTY)
        if (tag.contains("preview")) {
            ListTag plist = tag.getList("preview", 10); // 10 = CompoundTag
            for (int i = 0; i < Math.min(plist.size(), PREVIEW_SIZE); i++) {
                CompoundTag it = plist.getCompound(i);
                if (!it.isEmpty()) {
                    ItemStack s = ItemStack.parseOptional(provider, it);
                    c.preview[i] = (s == null) ? ItemStack.EMPTY : s;
                }
            }
        }
        return c;
    }
}

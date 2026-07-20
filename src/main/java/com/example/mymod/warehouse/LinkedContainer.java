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
    /** 服务端最近一次发的第一页前 PREVIEW_SIZE 个物品快照 (用于 tooltip). 客户端/服务端都填. */
    private ItemStack[] preview = new ItemStack[PREVIEW_SIZE];

    public LinkedContainer(UUID linkId, String name, ResourceKey<Level> dimension, BlockPos pos,
                           int slots, String blockId) {
        this.linkId = linkId;
        this.name = name;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.slots = slots;
        this.blockId = blockId;
        for (int i = 0; i < PREVIEW_SIZE; i++) preview[i] = ItemStack.EMPTY;
    }

    public UUID linkId() { return linkId; }
    public String name() { return name; }
    public ResourceKey<Level> dimension() { return dimension; }
    public BlockPos pos() { return pos; }
    public int slots() { return slots; }
    public String blockId() { return blockId; }

    public void setName(String name) { this.name = name; }

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
     */
    public List<net.minecraft.network.chat.Component> toTooltip() {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        lines.add(net.minecraft.network.chat.Component.literal(name));
        lines.add(net.minecraft.network.chat.Component.literal("§7" + slots + " 格"));

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
            lines.add(net.minecraft.network.chat.Component.literal("§7  (空)"));
        }
        return lines;
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
        LinkedContainer c = new LinkedContainer(
            tag.getUUID("id"),
            tag.getString("name"),
            net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(tag.getString("dim"))),
            new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
            tag.getInt("slots"),
            tag.getString("block")
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

package com.example.mymod.warehouse.client;

import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.LinkedContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧的 "已连接容器" / "待命名" / "升级状态" / "收容实体" 缓存. 仓库 UI 直接从这里读数据画图标.
 * <p>
 * 只有 {@link com.example.mymod.warehouse.network.S2CSyncLinkedContainers} 写 links,
 * {@link com.example.mymod.warehouse.network.S2COpenNameEntry} 写 pending,
 * {@link com.example.mymod.warehouse.network.S2CSyncUpgradeState} 写 upgrade,
 * {@link com.example.mymod.warehouse.network.S2CSyncEntityLinks} 写 entityLinks.
 */
public final class ClientWarehouseCache {
    private static final ThreadLocal<List<LinkedContainer>> LINKS =
        ThreadLocal.withInitial(Collections::emptyList);
    private static final ThreadLocal<List<EntityLink>> ENTITY_LINKS =
        ThreadLocal.withInitial(Collections::emptyList);
    private static UUID pendingLinkId;
    private static String pendingDefaultName = "";
    private static ItemStack pendingIcon = ItemStack.EMPTY;
    private static int pendingSlots = 0;
    /** 实体命名待处理: 玩家用收容器右键了实体后, 服务端让客户端开命名框. */
    private static UUID pendingEntityLinkId;
    private static String pendingEntityDefaultName = "";
    private static ItemStack pendingEntityIcon = ItemStack.EMPTY;
    /** 实体编辑待处理: 玩家右键实体图标, 服务端让客户端开编辑界面 (改名/解除). */
    private static UUID pendingEntityEditId;
    private static String pendingEntityEditCurrentName = "";
    private static ItemStack pendingEntityEditIcon = ItemStack.EMPTY;
    private static String pendingEntityEditTypeId = "";
    /** 升级状态. level=1 表示初始 1 级. */
    private static int upgradeLevel = 1;
    private static Map<Item, Integer> upgradeProgress = new LinkedHashMap<>();
    /**
     * 服务端收到 {@code C2SOpenUpgradeScreen} 后, 下一次 S2CSyncUpgradeState 到达时
     * 自动打开 UpgradeScreen. 用 boolean 避免多线程问题 (单客户端单线程).
     */
    public static volatile boolean requestOpenUpgrade = false;

    private ClientWarehouseCache() {}

    public static void setLinks(List<LinkedContainer> links) {
        LINKS.set(links);
    }

    public static List<LinkedContainer> getLinks() {
        return LINKS.get();
    }

    public static void setEntityLinks(List<EntityLink> links) {
        ENTITY_LINKS.set(links == null ? Collections.emptyList() : links);
    }

    public static List<EntityLink> getEntityLinks() {
        return ENTITY_LINKS.get();
    }

    public static void setPending(UUID linkId, String defaultName, ItemStack icon, int slots) {
        pendingLinkId = linkId;
        pendingDefaultName = defaultName;
        pendingIcon = icon;
        pendingSlots = slots;
    }

    public static void clearPending() {
        pendingLinkId = null;
        pendingDefaultName = "";
        pendingIcon = ItemStack.EMPTY;
        pendingSlots = 0;
    }

    public static UUID getPendingLinkId() { return pendingLinkId; }
    public static String getPendingDefaultName() { return pendingDefaultName; }
    public static ItemStack getPendingIcon() { return pendingIcon; }
    public static int getPendingSlots() { return pendingSlots; }

    public static void setPendingEntityName(UUID linkId, String defaultName, ItemStack icon) {
        pendingEntityLinkId = linkId;
        pendingEntityDefaultName = defaultName;
        pendingEntityIcon = icon;
    }

    public static UUID getPendingEntityLinkId() { return pendingEntityLinkId; }
    public static String getPendingEntityDefaultName() { return pendingEntityDefaultName; }
    public static ItemStack getPendingEntityIcon() { return pendingEntityIcon; }

    public static void clearPendingEntityName() {
        pendingEntityLinkId = null;
        pendingEntityDefaultName = "";
        pendingEntityIcon = ItemStack.EMPTY;
    }

    public static void setPendingEntityEdit(UUID linkId, String currentName, ItemStack icon, String typeId) {
        pendingEntityEditId = linkId;
        pendingEntityEditCurrentName = currentName;
        pendingEntityEditIcon = icon;
        pendingEntityEditTypeId = typeId;
    }

    public static UUID getPendingEntityEditId() { return pendingEntityEditId; }
    public static String getPendingEntityEditCurrentName() { return pendingEntityEditCurrentName; }
    public static ItemStack getPendingEntityEditIcon() { return pendingEntityEditIcon; }
    public static String getPendingEntityEditTypeId() { return pendingEntityEditTypeId; }

    public static void clearPendingEntityEdit() {
        pendingEntityEditId = null;
        pendingEntityEditCurrentName = "";
        pendingEntityEditIcon = ItemStack.EMPTY;
        pendingEntityEditTypeId = "";
    }

    public static void setUpgrade(int level, Map<Item, Integer> progress) {
        upgradeLevel = Math.max(1, Math.min(5, level));
        upgradeProgress = (progress != null) ? new LinkedHashMap<>(progress) : new LinkedHashMap<>();
    }

    public static int getUpgradeLevel() { return upgradeLevel; }
    public static Map<Item, Integer> getUpgradeProgress() { return upgradeProgress; }

    /**
     * 给 WarehouseMenu 构造客户端 stub 时用: 当前等级对应的"仓库本体总槽数".
     * 这样客户端 PagedItemHandler 能算出 pageCount, 不会因为 stub 太小导致 pageCount=1.
     */
    public static int getUpgradeLevelPageSize() {
        int pages = com.example.mymod.warehouse.WarehouseLevel.pagesFor(upgradeLevel);
        return pages * com.example.mymod.warehouse.PersonalWarehouseData.SLOTS_PER_PAGE;
    }
}

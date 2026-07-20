package com.example.mymod.warehouse.menu;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * 云存储菜单: 顶部 9x6 仓库格 (单页 54 槽) + 玩家背包 3x9 + 热栏 1x9.
 * <p>
 * 仓库本体 (level 1=1页, 2=5页, 3=20页, 4=50页, 5=100页) 通过
 * {@link PagedItemHandler} 包装, 一个菜单永远只显示 54 槽, 切页可访问全部.
 * <p>
 * 服务端 / 客户端都用同一个构造函数:
 * <ul>
 *   <li>服务端: {@code data = WarehouseDataManager.get(player)}, paged 包装真实 storage</li>
 *   <li>客户端: {@code data = null}, paged 包装一个 size = pages*54 的空 stub, 槽内容靠
 *       标准容器同步下发</li>
 * </ul>
 */
public class WarehouseMenu extends AbstractContainerMenu {
    public static final int ROWS = 6;
    public static final int COLS = 9;
    public static final int WAREHOUSE_SLOTS = ROWS * COLS; // 54

    public static final int WAREHOUSE_X = 8;
    public static final int WAREHOUSE_Y = 18;
    /** 玩家物品栏 Y 坐标 — 仓库底部 (18 + 6*18 = 126) 下方 12 像素, 避免重叠. */
    public static final int PLAYER_INV_X = 8;
    public static final int PLAYER_INV_Y = 138;
    public static final int HOTBAR_Y = PLAYER_INV_Y + 58;

    private final Player player;

    /** 当前页 (0-based). 服务端 / 客户端共享, 通过 C2SSwitchWarehousePage 同步. */
    private int currentPage = 0;
    /** 仓库本体分页包装. 槽 0~53 永远指 page=currentPage 那一页. */
    private final PagedItemHandler pagedStorage;

    public WarehouseMenu(MenuType<WarehouseMenu> type, int containerId, Inventory playerInventory) {
        super(type, containerId);
        this.player = playerInventory.player;

        // 服务端拿真实数据, 客户端拿空 stub. 但 stub 的大小要让 PagedItemHandler
        // 能算出 pageCount, 所以 stub 的 size 用 玩家当前等级对应的页数 * 54.
        int stubSize;
        if (player.level().isClientSide() || !(player instanceof ServerPlayer sp)) {
            stubSize = Math.max(WAREHOUSE_SLOTS,
                com.example.mymod.warehouse.client.ClientWarehouseCache.getUpgradeLevelPageSize());
        } else {
            PersonalWarehouseData data = WarehouseDataManager.get(sp.getUUID());
            stubSize = data.getStorage().getSlots();
        }
        IItemHandler backing = new ItemStackHandler(stubSize);
        this.pagedStorage = new PagedItemHandler(backing, WAREHOUSE_SLOTS);

        init(playerInventory);
    }

    /**
     * 1.21.1 {@link net.neoforged.neoforge.network.IContainerFactory} 的 2 参数 default
     * 版本: 客户端刚开菜单时, 服务端先发个 OpenMenu 包, 客户端用这个 2 参数构造器建占位.
     */
    public WarehouseMenu(int containerId, Inventory playerInventory) {
        this(MenuTypes.WAREHOUSE.get(), containerId, playerInventory);
    }

    private void init(Inventory playerInventory) {
        // 服务端: 把 pagedStorage 的 backing 换成真实 storage, 同时按当前等级重置 paged.
        // 客户端: 保持 init 阶段已建好的 pagedStorage (空 stub).
        if (!player.level().isClientSide() && player instanceof ServerPlayer sp) {
            PersonalWarehouseData data = WarehouseDataManager.get(sp.getUUID());
            // pagedStorage 的 source 字段是 private final, 但 PagedItemHandler 没暴露 setter.
            // 用反射替换 source 字段, 让 pagedStorage 现在指向真实数据.
            try {
                java.lang.reflect.Field f = PagedItemHandler.class.getDeclaredField("source");
                f.setAccessible(true);
                f.set(pagedStorage, data.getStorage());
                // 重置 page 到 0, 跟玩家当前页一致
                pagedStorage.setPage(currentPage);
            } catch (Exception e) {
                com.example.mymod.ExampleMod.LOGGER.error("[Warehouse] Failed to rebind paged storage", e);
            }
        }

        // 仓库 9x6 (单页 54 槽)
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slotIndex = col + row * COLS;
                int x = WAREHOUSE_X + col * 18;
                int y = WAREHOUSE_Y + row * 18;
                addSlot(new SlotItemHandler(pagedStorage, slotIndex, x, y));
            }
        }

        // 玩家背包 3x9
        IItemHandler playerHandler = new InvWrapper(playerInventory);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = col + row * 9 + 9;
                int x = PLAYER_INV_X + col * 18;
                int y = PLAYER_INV_Y + row * 18;
                addSlot(new SlotItemHandler(playerHandler, slotIndex, x, y));
            }
        }
        // 热栏 1x9
        for (int col = 0; col < 9; col++) {
            int x = PLAYER_INV_X + col * 18;
            addSlot(new SlotItemHandler(playerHandler, col, x, HOTBAR_Y));
        }
    }

    public Player getPlayer() { return player; }

    public PagedItemHandler pagedStorage() { return pagedStorage; }
    public int currentPage() { return currentPage; }
    public int pageCount() { return pagedStorage.getPageCount(); }
    public int totalSlots() { return pagedStorage.getSourceSlots(); }

    /**
     * 服务端 / 客户端共用: 切换页码后调一次, 让所有槽位重新 sync.
     * 服务端在收到 {@code C2SSwitchWarehousePage} 包时调, 客户端在收到
     * {@code S2CSyncWarehousePage} 包时调.
     */
    public void setPage(int newPage) {
        int before = currentPage;
        pagedStorage.setPage(newPage);
        currentPage = pagedStorage.getPage();
        if (before == currentPage) return;
        // 标记所有槽位 changed, 触发 AbstractContainerMenu.broadcastChanges
        // 在 server 端会通过 SetSlot/ContainerSetContent 重发一次
        for (net.minecraft.world.inventory.Slot s : this.slots) s.setChanged();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        var slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int warehouseEnd = WAREHOUSE_SLOTS;
        int hotbarEnd = warehouseEnd + 36;

        if (index < warehouseEnd) {
            if (!moveItemStackTo(stack, warehouseEnd, hotbarEnd, true)) return ItemStack.EMPTY;
        } else if (index < hotbarEnd) {
            if (!moveItemStackTo(stack, 0, warehouseEnd, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** NetworkHooks.openScreen 用的 MenuProvider. */
    public static class Provider implements MenuProvider {
        @Override
        public Component getDisplayName() {
            return Component.translatable("container." + ExampleMod.MOD_ID + ".warehouse");
        }

        @Override
        public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
            return new WarehouseMenu(MenuTypes.WAREHOUSE.get(), containerId, playerInventory);
        }
    }
}

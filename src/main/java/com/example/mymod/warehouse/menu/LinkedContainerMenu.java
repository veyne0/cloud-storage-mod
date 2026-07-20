package com.example.mymod.warehouse.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * 玩家从仓库界面点开"已连接的远距离容器"时用的菜单.
 * <p>
 * <b>关键: {@link #stillValid} 直接返回 true</b> —— 玩家在世界任何位置都能打开
 * 已连接的容器, 不受原版大箱子 8 格距离检查的限制.
 * <p>
 * <b>固定 6 行 54 槽容器视图</b>: 任何大于 54 槽的容器 (铁箱子 63 槽, 钻石箱 90 槽, ...)
 * 都按 6 行 9 列 / 54 槽 / 页显示, 切换页码访问其余部分. 这是
 * 通过 {@link PagedItemHandler} 包装器实现的, 槽位 i 实际对应到源 handler
 * 的 {@code page * 54 + i}.
 * <p>
 * <b>槽数同步</b>: 服务端在 {@code openMenu} 的 {@code Consumer<RegistryFriendlyByteBuf>}
 * 里把 {@code handler.getSlots()} 写进 OpenMenu 包的 buf —— 客户端
 * {@link MenuTypes#LINKED} 注册的 {@code IContainerFactory.create(int, Inventory, buf)}
 * 会从 buf 读出来, client 和 server 的 PagedItemHandler 包装的源 handler 槽数一致.
 */
public class LinkedContainerMenu extends AbstractContainerMenu {
    /** 容器部分固定 6 行 (54 槽). */
    public static final int ROWS_PER_PAGE = 6;
    public static final int SLOTS_PER_PAGE = ROWS_PER_PAGE * 9; // 54

    private int currentPage = 0;
    private final PagedItemHandler pagedHandler;

    public LinkedContainerMenu(MenuType<LinkedContainerMenu> type, int containerId,
                               Inventory playerInv, int containerSlots, IItemHandler sourceHandler) {
        super(type, containerId);
        IItemHandler source = sourceHandler != null
            ? sourceHandler
            : new ItemStackHandler(containerSlots);
        this.pagedHandler = new PagedItemHandler(source, SLOTS_PER_PAGE);

        // 容器部分固定 6 行 9 列 54 槽
        for (int r = 0; r < ROWS_PER_PAGE; r++) {
            for (int c = 0; c < 9; c++) {
                int idx = r * 9 + c;
                addSlot(new SlotItemHandler(pagedHandler, idx, 8 + c * 18, 18 + r * 18));
            }
        }
        // 玩家物品栏 3 行
        int playerInvY = 18 + ROWS_PER_PAGE * 18 + 14;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(playerInv, 9 + r * 9 + c, 8 + c * 18, playerInvY + r * 18));
            }
        }
        // 热栏 1 行
        int hotbarY = playerInvY + 58;
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(playerInv, c, 8 + c * 18, hotbarY));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // 无距离限制
    }

    public int currentPage() { return currentPage; }
    public int pageCount() { return pagedHandler.getPageCount(); }
    public int totalSlots() { return pagedHandler.getSourceSlots(); }
    public PagedItemHandler pagedHandler() { return pagedHandler; }

    /**
     * 服务端 / 客户端共用: 切换页码后调一次, 让所有槽位重新 sync.
     * 服务端在收到 {@code C2SSwitchLinkedContainerPage} 包时调, 客户端在收到
     * {@code S2CSyncLinkedContainerPage} 包时调.
     */
    public void setPage(int newPage) {
        int before = currentPage;
        pagedHandler.setPage(newPage);
        currentPage = pagedHandler.getPage();
        if (before == currentPage) return;
        // 标记所有槽位 changed, 触发 AbstractContainerMenu.broadcastChanges
        // 在 server 端会通过 SetSlot/ContainerSetContent 重发一次
        for (Slot s : this.slots) s.setChanged();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // shift-click: 容器 ↔ 玩家物品栏
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int containerEnd = SLOTS_PER_PAGE; // 54
            if (index < containerEnd) {
                if (!this.moveItemStackTo(stack, containerEnd, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, containerEnd, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return result;
    }
}

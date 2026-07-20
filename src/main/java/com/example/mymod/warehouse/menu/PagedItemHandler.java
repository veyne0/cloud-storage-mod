package com.example.mymod.warehouse.menu;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 把一个任意槽数 {@link IItemHandler} 包装成"分页视图": 总是对外暴露
 * {@link #pageSize} 个槽位 (默认 54 = 9×6), 改 {@link #setPage} 后, 槽位 i
 * 实际对应到源 handler 的 {@code page * pageSize + i}.
 * <p>
 * 用于: 大于 54 槽的远距离容器 (例如铁箱子 9×7=63, 9×8=72, 钻石箱 9×10=90)
 * 在 mod 自己的 UI 里能正常显示 —— 一个菜单只装 54 槽, 切换页码即可访问全部.
 */
public class PagedItemHandler implements IItemHandlerModifiable {
    public static final int DEFAULT_PAGE_SIZE = 54;
    private final IItemHandler source;
    private final int pageSize;
    private int page;

    public PagedItemHandler(IItemHandler source, int pageSize) {
        this.source = source;
        this.pageSize = pageSize;
    }

    public PagedItemHandler(IItemHandler source) {
        this(source, DEFAULT_PAGE_SIZE);
    }

    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public int getSourceSlots() { return source.getSlots(); }

    /** 总页数. */
    public int getPageCount() {
        return Math.max(1, (source.getSlots() + pageSize - 1) / pageSize);
    }

    public void setPage(int page) {
        int n = getPageCount();
        this.page = Math.max(0, Math.min(page, n - 1));
    }

    @Override
    public int getSlots() { return pageSize; }

    private int realIndex(int slot) {
        return page * pageSize + slot;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return ItemStack.EMPTY;
        return source.getStackInSlot(r);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return stack;
        return source.insertItem(r, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return ItemStack.EMPTY;
        return source.extractItem(r, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return 0;
        return source.getSlotLimit(r);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return false;
        return source.isItemValid(r, stack);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int r = realIndex(slot);
        if (r < 0 || r >= source.getSlots()) return;
        if (source instanceof IItemHandlerModifiable m) {
            // 走源 handler 的 setStackInSlot (覆盖, 不管是否能堆叠)
            m.setStackInSlot(r, stack);
        } else {
            // fallback: 源 handler 不可变, 只能尝试 insertItem. 如果是 EMPTY,
            // insertItem 会跳过; 如果有同种, 会堆叠. 否则不动.
            ItemStack cur = source.getStackInSlot(r);
            if (cur.isEmpty()) {
                source.insertItem(r, stack, false);
            } else if (ItemStack.isSameItemSameComponents(cur, stack)
                       && cur.getCount() < cur.getMaxStackSize()) {
                int can = Math.min(stack.getCount(), cur.getMaxStackSize() - cur.getCount());
                if (can > 0) {
                    ItemStack copy = stack.copy();
                    copy.setCount(can);
                    source.insertItem(r, copy, false);
                }
            }
        }
    }
}

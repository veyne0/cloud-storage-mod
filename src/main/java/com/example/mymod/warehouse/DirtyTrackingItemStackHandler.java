package com.example.mymod.warehouse;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 普通的 {@link ItemStackHandler} 不会自动通知 "数据脏了", 玩家在菜单里拿/放物品
 * 不会触发 {@code SavedData.setDirty()}, 重启游戏后物品就丢了.
 * <p>
 * 这个子类重写 {@code onContentsChanged(int)}, 一旦槽位内容变化, 立刻
 * 调 {@link WarehouseDataManager#setDirty()}, 让世界存盘.
 */
public class DirtyTrackingItemStackHandler extends ItemStackHandler {
    public DirtyTrackingItemStackHandler(int size) { super(size); }

    @Override
    protected void onContentsChanged(int slot) {
        super.onContentsChanged(slot);
        // 任何槽位变化都标脏, 玩家放/拿/合并/shift 都会触发.
        WarehouseDataManager.setDirty();
    }

    @Override
    public void setSize(int newSize) {
        super.setSize(newSize);
        // 升级扩容后槽数变了, 也要落盘.
        WarehouseDataManager.setDirty();
    }
}

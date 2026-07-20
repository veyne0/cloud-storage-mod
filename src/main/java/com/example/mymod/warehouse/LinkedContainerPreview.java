package com.example.mymod.warehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/** 工具: 给一组已连接容器填上"前 PREVIEW_SIZE 个物品"快照, 发给客户端用. */
public final class LinkedContainerPreview {
    private LinkedContainerPreview() {}

    /**
     * 就地修改 {@code links} 里每个 link 的 preview 字段: 调用 {@code be.getCapability}
     * 拿到真实 IItemHandler, 读 slot 0..8 物品. 容器不可达 (be == null) 时全部 EMPTY.
     */
    public static void fillPreviews(ServerLevel level, List<LinkedContainer> links) {
        for (LinkedContainer link : links) {
            try {
                BlockPos pos = link.pos();
                BlockEntity be = level.getBlockEntity(pos);
                ItemStack[] items = new ItemStack[LinkedContainer.PREVIEW_SIZE];
                if (be == null) {
                    for (int i = 0; i < items.length; i++) items[i] = ItemStack.EMPTY;
                } else {
                    IItemHandler h = Capabilities.ItemHandler.BLOCK.getCapability(
                        level, pos, be.getBlockState(), be, null);
                    if (h == null) {
                        for (int i = 0; i < items.length; i++) items[i] = ItemStack.EMPTY;
                    } else {
                        int n = Math.min(items.length, h.getSlots());
                        for (int i = 0; i < n; i++) {
                            ItemStack s = h.getStackInSlot(i);
                            items[i] = (s == null) ? ItemStack.EMPTY : s.copy();
                        }
                        for (int i = n; i < items.length; i++) items[i] = ItemStack.EMPTY;
                    }
                }
                link.setPreviewArray(items);
            } catch (Exception e) {
                for (int i = 0; i < LinkedContainer.PREVIEW_SIZE; i++) link.setPreview(i, ItemStack.EMPTY);
            }
        }
    }
}

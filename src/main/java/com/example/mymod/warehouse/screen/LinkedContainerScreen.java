package com.example.mymod.warehouse.screen;

import com.example.mymod.warehouse.menu.LinkedContainerMenu;
import com.example.mymod.warehouse.network.C2SSwitchLinkedContainerPage;
import com.example.mymod.warehouse.network.WarehouseNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 已连接容器的界面. 远距离打开, 无 stillValid 距离检查 (服务端 menu 自己控制).
 * <p>
 * 固定 6 行 9 列容器部分 (54 槽 / 页), 大于 54 槽的容器通过
 * 顶部 "&lt;" / "&gt;" 按钮翻页. 每页固定大小, 所以 imageHeight 也是固定的.
 * <p>
 * 注意: AbstractContainerScreen.render 内部已经会调 renderLabels 画 title 和
 * "物品栏"标签, 这里只补画分页按钮 + 当前页/总页数, 不重复画"物品栏".
 */
public class LinkedContainerScreen extends AbstractContainerScreen<LinkedContainerMenu> {
    private static final int COLOR_BG = 0xFF_C6C6C6;
    private static final int COLOR_PANEL = 0xFF_B0B0B0;
    private static final int COLOR_PANEL_DARK = 0xFF_8B8B8B;
    private static final int COLOR_TEXT = 0xFF_202020;
    private static final int COLOR_TEXT_DIM = 0xFF_5A5A5A;

    private static final int CONTAINER_PX_W = 9 * 18 + 16; // 178
    private static final int ROWS = LinkedContainerMenu.ROWS_PER_PAGE; // 6
    private static final int CONTAINER_PX_H = 18 + ROWS * 18 + 14; // 18+108+14=140
    private static final int INV_PX_H = 76 + 8;
    private static final int SCREEN_H = CONTAINER_PX_H + INV_PX_H; // 224

    private static final int PAGE_BTN_W = 20;
    private static final int PAGE_BTN_H = 18;

    public LinkedContainerScreen(LinkedContainerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = CONTAINER_PX_W;
        this.imageHeight = SCREEN_H;
        this.inventoryLabelY = this.imageHeight - 94;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        rebuildPageButtons();
    }

    private void rebuildPageButtons() {
        clearWidgets();
        int pageCount = menu.pageCount();
        if (pageCount <= 1) return; // 只有 1 页就不画按钮

        int y = topPos - 22; // 按钮在屏幕顶端 (title 上方)
        // 居中摆放: [ < ] page/total [ > ]
        int totalW = PAGE_BTN_W + 8 + 40 + 8 + PAGE_BTN_W;
        int startX = (this.width - totalW) / 2;
        int btnY = Math.max(2, y);

        Button prev = Button.builder(Component.literal("<"), b -> {
            if (menu.currentPage() > 0) {
                PacketDistributor.sendToServer(new C2SSwitchLinkedContainerPage(menu.currentPage() - 1));
            }
        }).bounds(startX, btnY, PAGE_BTN_W, PAGE_BTN_H).build();

        Button next = Button.builder(Component.literal(">"), b -> {
            if (menu.currentPage() < pageCount - 1) {
                PacketDistributor.sendToServer(new C2SSwitchLinkedContainerPage(menu.currentPage() + 1));
            }
        }).bounds(startX + PAGE_BTN_W + 8 + 40 + 8, btnY, PAGE_BTN_W, PAGE_BTN_H).build();

        addRenderableWidget(prev);
        addRenderableWidget(next);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 背景
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COLOR_BG);
        g.fill(leftPos + 6, topPos + 14, leftPos + imageWidth - 6, topPos + 14 + ROWS * 18 + 4, COLOR_PANEL);
        g.fill(leftPos + 6, topPos + 14 + ROWS * 18 + 12, leftPos + imageWidth - 6,
            topPos + 14 + ROWS * 18 + 12 + 76, COLOR_PANEL);

        // 容器槽位 6 行 9 列
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < 9; c++) {
                drawSlot(g, leftPos + 8 + c * 18, topPos + 18 + r * 18);
            }
        }
        // 玩家物品栏 3 行
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                drawSlot(g, leftPos + 8 + c * 18,
                    topPos + 18 + ROWS * 18 + 14 + r * 18);
            }
        }
        // 热栏 1 行
        for (int c = 0; c < 9; c++) {
            drawSlot(g, leftPos + 8 + c * 18, topPos + imageHeight - 28);
        }
    }

    private void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, COLOR_PANEL_DARK);
        g.fill(x, y, x + 18, y + 1, 0xFF_AEAEAE);
        g.fill(x, y, x + 1, y + 18, 0xFF_AEAEAE);
        g.fill(x, y + 17, x + 18, y + 18, 0xFF_5A5A5A);
        g.fill(x + 17, y, x + 18, y + 18, 0xFF_5A5A5A);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 在 title 旁边显示 "1/N" — 但 AbstractContainerScreen 已经画了 title 在 titleLabelY,
        // 我们在 page 按钮 (init 里加的) 之间已经覆盖了 page 数字. 这里只补"分页: x/N" 文字.
        int pageCount = menu.pageCount();
        if (pageCount > 1) {
            int btnY = Math.max(2, topPos - 22);
            int totalW = PAGE_BTN_W + 8 + 40 + 8 + PAGE_BTN_W;
            int startX = (this.width - totalW) / 2;
            String s = (menu.currentPage() + 1) + "/" + pageCount;
            int textW = font.width(s);
            g.drawString(font, s, startX + PAGE_BTN_W + 8 + (40 - textW) / 2,
                btnY + (PAGE_BTN_H - 8) / 2, COLOR_TEXT, false);
        }
    }
}

package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.LinkedContainer;
import com.example.mymod.warehouse.client.ClientWarehouseCache;
import com.example.mymod.warehouse.menu.WarehouseMenu;
import com.example.mymod.warehouse.network.C2SOpenLinkedContainer;
import com.example.mymod.warehouse.network.C2SOpenUpgradeScreen;
import com.example.mymod.warehouse.network.C2SRequestEditLinked;
import com.example.mymod.warehouse.network.C2SSwitchWarehousePage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 云存储主界面.
 * <p>
 * 布局 (按"清爽、白灰、大按钮、左右分块、铺满右半"的用户偏好):
 * <pre>
 * +----------------------------------+----------------+
 * | 大型箱子     [ &lt; ] 1/1 [ &gt; ]     | 已连接 [1/N &gt;] |
 * | +--+--+--+--+--+--+--+--+--+     | +--+--+--+--+ |
 * | |  |  |  |  |  |  |  |  |  |     | |  |  |  |  | |
 * | +--+--+--+--+--+--+--+--+--+     | +--+--+--+--+ |
 * | |  |  |  |  |  |  |  |  |  |     | |  |  |  |  | |
 * | +--+--+--+--+--+--+--+--+--+     | +--+--+--+--+ |
 * | |  |  |  |  |  |  |  |  |  |     | ...            |
 * | +--+--+--+--+--+--+--+--+--+     | +--+--+--+--+ |
 * | |  |  |  |  |  |  |  |  |  |     | |  |  |  |  | |
 * | +--+--+--+--+--+--+--+--+--+     | +--+--+--+--+ |
 * | |  |  |  |  |  |  |  |  |  |     |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | 物品栏                          | (32个/页, 可翻) |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | [H][H][H][H][H][H][H][H][H]      |                |
 * +----------------------------------+----------------+
 * </pre>
 *
 * <p>关键: <b>imageWidth = this.width</b>, 让屏幕铺满整个游戏窗口宽度,
 * 这样 JEI (它会自己贴在屏幕最右侧 ~200px) 被挤出可见区域, 不会被 JEI 占空间.
 * 如果想恢复 JEI, 把 imageWidth 改回 332 即可.
 *
 * <p>已连接容器支持 <b>翻页</b>: 每页 32 个 (4列 x 8行), 顶部分页按钮 "&lt; 1/N &gt;".
 * 玩家走到世界任何位置都能打开已连接的容器, 没有任何距离限制 (跨维度会提示).
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {
    // ===== 配色 (白灰) =====
    private static final int COLOR_BG = 0xFF_C6C6C6;
    private static final int COLOR_PANEL = 0xFF_B0B0B0;
    private static final int COLOR_PANEL_DARK = 0xFF_8B8B8B;
    private static final int COLOR_DIVIDER = 0xFF_5A5A5A;
    private static final int COLOR_TEXT = 0xFF_202020;
    private static final int COLOR_TEXT_DIM = 0xFF_5A5A5A;
    private static final int COLOR_BTN_HOVER = 0xFF_D0D0D0;
    private static final int COLOR_BTN_NORMAL = 0xFF_8E8E8E;

    // ===== 布局: 主区 =====
    /** 主区宽 (仓库 9x6 + 玩家物品栏 9x4 + 热栏 9x1) — 固定 9 列, 加上 padding. */
    private static final int MAIN_W = 9 * 18 + 16; // 178

    // ===== 布局: 已连接侧栏 (动态) =====
    /** 已连接侧栏的列数 (运行时按可用宽度算). */
    private int linkCols = 4;
    /** 已连接侧栏的行数 (每页). */
    private static final int LINK_ROWS = 8;
    /** 每页图标数 = linkCols * LINK_ROWS. */
    private int linksPerPage = linkCols * LINK_ROWS;
    /** 图标格子大小 (含 padding). */
    private static final int ICON_CELL = 20; // 18 + 2 padding
    /** 侧栏宽 (运行时: 整个屏幕宽 - 主区 - 间隔, 让侧栏铺满右半). */
    private int sideW = 88;
    /** 屏幕高. 240=仓库+物品栏+热栏+升级按钮+底部留白, 不重叠. */
    private static final int SCREEN_H = 246;

    // ===== 布局: 仓库右上分页 (主区分页, 仓库本体) =====
    private static final int PAGE_BTN_W = 14;
    private static final int PAGE_BTN_H = 14;
    /** 分页按钮顶部 Y 偏移, 比 titleLabelY 略低, 避免和左上角标题叠在一起. */
    private static final int PAGE_BTN_Y = 7;

    // ===== 布局: 已连接翻页按钮 (侧栏上方) =====
    private static final int SIDE_PAGE_BTN_W = 12;
    private static final int SIDE_PAGE_BTN_H = 12;

    private final List<Rect> iconRects = new ArrayList<>();
    /** 已连接容器当前页 (0-based). */
    private int linkPage = 0;

    /**
     * 玩家点击某个已连接容器图标, 实际容器会在服务端开出一个独立菜单.
     * 关闭时, 我们希望回到本仓库界面而不是退到游戏世界.
     * <p>
     * 在 {@link #mouseClicked} 里把 {@code this} 写进这个静态字段;
     * {@link com.example.mymod.warehouse.mixin.MinecraftScreenMixin} 会在玩家
     * 关闭刚开出来的容器时, 自动把屏切回这个 pending.
     */
    private static Screen pendingReturn;

    /** 取走 pending 引用 (原子地). */
    public static Screen consumePendingReturn() {
        Screen s = pendingReturn;
        pendingReturn = null;
        return s;
    }

    /** 清掉 pending (例如玩家手动打开别的屏, 不再自动恢复). */
    public static void clearPendingReturn() {
        pendingReturn = null;
    }

    public WarehouseScreen(WarehouseMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        // 屏幕高固定 240 (宽要在 init() 后才能取到 this.width, 见 init())
        this.imageHeight = SCREEN_H;
        // 物品栏标签: 放在玩家背包上方 8 像素处. 玩家背包 Y=112, 所以标签 Y=104.
        this.inventoryLabelY = WarehouseMenu.PLAYER_INV_Y - 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // 关键: 在 init() 后 this.width / this.height 才是真实游戏窗口尺寸.
        // 把 imageWidth 设为整个窗口宽, UI 铺满整屏, JEI 的默认占位 (屏幕最右 ~200px)
        // 就被我们的"已连接"侧栏区域覆盖 (z-order 在 super.render 之后画).
        this.imageWidth = this.width;
        this.leftPos = 0;
        this.topPos = (this.height - this.imageHeight) / 2;
        // 侧栏铺满右半: 整个屏幕宽 - 主区 - 8 间隔.
        // 列数 = (侧栏宽 - 16 padding) / 格子大小, 至少 4.
        this.sideW = Math.max(120, this.width - MAIN_W - 8);
        this.linkCols = Math.max(4, (this.sideW - 16) / ICON_CELL);
        this.linksPerPage = this.linkCols * LINK_ROWS;
        rebuildButtons();
    }

    @Override
    protected void rebuildWidgets() {
        // 重建按钮 (原版 Screen.rebuildWidgets 是 protected, 在 init() 时调用).
        rebuildButtons();
    }

    /** 公有方法, 方便外部包 (网络包 handler) 在切页时强制重建按钮. */
    public void refreshWidgets() {
        rebuildButtons();
    }

    /** 重建所有按钮 (因为在 init() 后才能拿到 width, 重新构建一次确保尺寸正确). */
    private void rebuildButtons() {
        clearWidgets();
        // ---- 主区分页按钮 (仓库本体, 按等级显示总页数) ----
        int mainTotalPages = Math.max(1, menu.pageCount());
        int mainPageY = topPos + PAGE_BTN_Y;
        // 紧贴主区右边缘: <  [1/N]  >, 标题在左上角不再被压
        int nextMainX = MAIN_W - PAGE_BTN_W - 4;
        int labelMainX = nextMainX - 32;
        int prevMainX = labelMainX - PAGE_BTN_W - 4;
        // "<" 按钮: 在第 0 页时禁用
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (menu.currentPage() > 0) {
                PacketDistributor.sendToServer(new C2SSwitchWarehousePage(menu.currentPage() - 1));
            }
        }).bounds(leftPos + prevMainX, mainPageY, PAGE_BTN_W, PAGE_BTN_H).build());
        // ">" 按钮: 在最后一页时禁用
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (menu.currentPage() < mainTotalPages - 1) {
                PacketDistributor.sendToServer(new C2SSwitchWarehousePage(menu.currentPage() + 1));
            }
        }).bounds(leftPos + nextMainX, mainPageY, PAGE_BTN_W, PAGE_BTN_H).build());
        this.mainLabelX = leftPos + labelMainX + 4;
        this.mainLabelY = mainPageY + 3;
        this.mainTotalPages = mainTotalPages;
        // ---- 侧栏翻页按钮 ----
        int sideX = leftPos + MAIN_W + 8;
        int sideY = topPos + 4;
        int sidePageTotal = totalLinkPages();
        Button prevBtn = Button.builder(Component.literal("<"), b -> {
            if (linkPage > 0) linkPage--;
            rebuildButtons();
        }).bounds(sideX, sideY, SIDE_PAGE_BTN_W, SIDE_PAGE_BTN_H).build();
        Button nextBtn = Button.builder(Component.literal(">"), b -> {
            if (linkPage < sidePageTotal - 1) linkPage++;
            rebuildButtons();
        }).bounds(sideX + sideW - SIDE_PAGE_BTN_W, sideY, SIDE_PAGE_BTN_W, SIDE_PAGE_BTN_H).build();
        if (sidePageTotal > 0) {
            addRenderableWidget(prevBtn);
            addRenderableWidget(nextBtn);
        }
        this.sideLabelX = sideX + SIDE_PAGE_BTN_W + 4;
        this.sideLabelY = sideY + 2;
        this.sidePageTotal = sidePageTotal;
        // ---- 升级按钮 (侧栏底部, 满级时变灰不可点) ----
        int upgW = 56;
        int upgH = 18;
        int upgX = sideX + sideW - upgW - 4;
        int upgY = topPos + SCREEN_H - upgH - 6;
        boolean isMax = ClientWarehouseCache.getUpgradeLevel() >= 5;
        addRenderableWidget(Button.builder(
            Component.translatable("gui." + ExampleMod.MOD_ID + ".warehouse.upgrade"
                + (isMax ? "_max" : "")),
            b -> {
                if (isMax) return;
                PacketDistributor.sendToServer(new C2SOpenUpgradeScreen());
            })
            .bounds(upgX, upgY, upgW, upgH)
            .build());
        // 等级标签: 放在升级按钮左侧, 大字
        gUpgradeLabelX = sideX + 4;
        gUpgradeLabelY = upgY + 5;
    }

    private int mainLabelX, mainLabelY;
    private int mainTotalPages;
    private int sideLabelX, sideLabelY;
    private int sidePageTotal;
    private int gUpgradeLabelX, gUpgradeLabelY;

    private int totalLinkPages() {
        int n = ClientWarehouseCache.getLinks().size();
        return Math.max(1, (n + linksPerPage - 1) / linksPerPage);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // 背景铺满
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COLOR_BG);
        // ---- 主区: 仓库 + 玩家物品栏 + 热栏 ----
        // 仓库面板: 紧贴 WAREHOUSE_Y 上下留 padding
        g.fill(leftPos + 6, topPos + 14, leftPos + 6 + MAIN_W - 12, topPos + 14 + 108, COLOR_PANEL);
        // 玩家物品栏 + 热栏 面板: 跟着 PLAYER_INV_Y 走 (向上移动后这里也跟着上移)
        int invPanelY = topPos + WarehouseMenu.PLAYER_INV_Y - 4;
        int invPanelH = (WarehouseMenu.HOTBAR_Y + 18) - (WarehouseMenu.PLAYER_INV_Y - 4);
        g.fill(leftPos + 6, invPanelY, leftPos + 6 + MAIN_W - 12, invPanelY + invPanelH, COLOR_PANEL);
        drawSlotGrid(g, WarehouseMenu.WAREHOUSE_X, WarehouseMenu.WAREHOUSE_Y, 9, 6);
        drawSlotGrid(g, WarehouseMenu.PLAYER_INV_X, WarehouseMenu.PLAYER_INV_Y, 9, 3);
        for (int col = 0; col < 9; col++) {
            drawSlot(g, leftPos + WarehouseMenu.PLAYER_INV_X + col * 18, topPos + WarehouseMenu.HOTBAR_Y);
        }
        // ---- 分割线 ----
        int divX = leftPos + MAIN_W + 4;
        g.fill(divX, topPos + 6, divX + 2, topPos + imageHeight - 6, COLOR_DIVIDER);
        // ---- 侧栏背景 ----
        int sideX = leftPos + MAIN_W + 8;
        int sideY = topPos + 4;
        int sideH = SIDE_PAGE_BTN_H + 6 + LINK_ROWS * ICON_CELL + 4;
        g.fill(sideX, sideY, sideX + sideW, sideY + sideH, COLOR_PANEL);
    }

    private void drawSlotGrid(GuiGraphics g, int x0, int y0, int cols, int rows) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                drawSlot(g, leftPos + x0 + c * 18, topPos + y0 + r * 18);
            }
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
        iconRects.clear();
        // super.render() 已经画了标题 (云存储) 和 物品栏/Inventory 标签,
        // 不要再画第二遍 — 重复绘制会让字看起来"叠在一起".
        // 这里只画我们自定义的标签:
        //   - 主区分页 "1/N" (super 不会画)
        //   - 侧栏标题 "已连接" (super 不会画)
        //   - 侧栏分页 "1/N" (super 不会画)
        //   - 升级等级 "Lv.N"
        // 主区分页标签: 显示当前页 / 总页数 (按等级, Lv.2=5 页, Lv.3=20 页, 等等)
        g.drawString(font, (menu.currentPage() + 1) + "/" + mainTotalPages,
            mainLabelX, mainLabelY, COLOR_TEXT, false);
        // 侧栏标题 + 分页标签
        int sideX = leftPos + MAIN_W + 8;
        int sideY = topPos + 4;
        g.drawString(font, Component.translatable("gui." + ExampleMod.MOD_ID + ".warehouse.linked"),
            sideX, sideY + SIDE_PAGE_BTN_H + 6, COLOR_TEXT, false);
        g.drawString(font, linkPage + 1 + "/" + sidePageTotal, sideLabelX, sideLabelY, COLOR_TEXT, false);
        // 等级标签 (侧栏底部, 升级按钮左边)
        g.drawString(font, "Lv." + ClientWarehouseCache.getUpgradeLevel(),
            gUpgradeLabelX, gUpgradeLabelY, COLOR_TEXT, false);
        // ---- 已连接图标网格 ----
        var links = ClientWarehouseCache.getLinks();
        int startIdx = linkPage * linksPerPage;
        int endIdx = Math.min(links.size(), startIdx + linksPerPage);
        int gridX = sideX + 4;
        int gridY = sideY + SIDE_PAGE_BTN_H + 18; // 标题下方
        for (int i = startIdx; i < endIdx; i++) {
            var link = links.get(i);
            int idx = i - startIdx;
            int col = idx % linkCols;
            int row = idx / linkCols;
            int bx = gridX + col * ICON_CELL;
            int by = gridY + row * ICON_CELL;
            Rect rect = new Rect(bx, by, ICON_CELL - 2, ICON_CELL - 2, link);
            iconRects.add(rect);
            boolean hover = rect.contains(mouseX, mouseY);
            g.fill(bx, by, bx + ICON_CELL - 2, by + ICON_CELL - 2,
                hover ? COLOR_BTN_HOVER : COLOR_BTN_NORMAL);
            g.fill(bx, by, bx + ICON_CELL - 2, by + 1, 0xFF_404040);
            g.fill(bx, by + ICON_CELL - 3, bx + ICON_CELL - 2, by + ICON_CELL - 2, 0xFF_404040);
            g.fill(bx, by, bx + 1, by + ICON_CELL - 2, 0xFF_404040);
            g.fill(bx + ICON_CELL - 3, by, bx + ICON_CELL - 2, by + ICON_CELL - 2, 0xFF_404040);
            ItemStack icon = blockIdToStack(link.blockId());
            g.renderItem(icon, bx + (ICON_CELL - 2 - 16) / 2, by + (ICON_CELL - 2 - 16) / 2);
        }
        // ---- hover tooltip ----
        for (Rect r : iconRects) {
            if (r.contains(mouseX, mouseY)) {
                // 1.21.1 的 renderTooltip(Font, List<Component>, int, int) 没了,
                // 改成 List<? extends FormattedCharSequence>; 把 Component 转成视觉字符序列.
                List<FormattedCharSequence> tipLines = new ArrayList<>();
                for (Component c : r.link.toTooltip()) {
                    tipLines.add(c.getVisualOrderText());
                }
                g.renderTooltip(font, tipLines, mouseX, mouseY);
                break;
            }
        }
    }

    private static ItemStack blockIdToStack(String blockId) {
        try {
            ResourceLocation id = ResourceLocation.parse(blockId);
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) return new ItemStack(Items.BARRIER);
            return new ItemStack(item);
        } catch (Exception e) {
            return new ItemStack(Items.BARRIER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Rect r : iconRects) {
            if (r.contains((int) mouseX, (int) mouseY)) {
                // 0 = 左键 (打开容器), 1 = 右键 (打开编辑界面: 改名字 / 解除连接)
                if (button == 1) {
                    // 编辑界面是 GUI 屏, 走 parent 字段返回
                    PacketDistributor.sendToServer(new C2SRequestEditLinked(r.link.linkId()));
                } else {
                    // 实际容器开出来是 AbstractContainerScreen, 用 pendingReturn
                    // 标记一下, 关容器时 Mixin 会自动回到本仓库
                    pendingReturn = this;
                    PacketDistributor.sendToServer(new C2SOpenLinkedContainer(r.link.linkId()));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private record Rect(int x, int y, int w, int h, LinkedContainer link) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}

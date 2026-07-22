package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.EntityLink;
import com.example.mymod.warehouse.LinkedContainer;
import com.example.mymod.warehouse.client.ClientWarehouseCache;
import com.example.mymod.warehouse.menu.WarehouseMenu;
import com.example.mymod.warehouse.network.C2SOpenEntityEdit;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 云存储主界面.
 * <p>
 * 布局 (按"清爽、白灰、大按钮、左右分块、铺满右半"的用户偏好):
 * <pre>
 * +----------------------------------+----------------+
 * | 大型箱子     [ &lt; ] 1/1 [ &gt; ]     | [容器][实体]    |  ← 顶部 tab 切换
 * | +--+--+--+--+--+--+--+--+--+     | [ &lt; 1/N &gt; ]   |  ← 翻页按钮
 * | |  |  |  |  |  |  |  |  |  |     | +--+--+--+--+ |
 * | +--+--+--+--+--+--+--+--+--+     | |  |  |  |  | |
 * | |  |  |  |  |  |  |  |  |  |     | +--+--+--+--+ |
 * | +--+--+--+--+--+--+--+--+--+     | |  |  |  |  | |
 * | |  |  |  |  |  |  |  |  |  |     | +--+--+--+--+ |
 * | +--+--+--+--+--+--+--+--+--+     | ...            |
 * | |  |  |  |  |  |  |  |  |  |     | +--+--+--+--+ |
 * | +--+--+--+--+--+--+--+--+--+     | |  |  |  |  | |
 * | 物品栏                          |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | +--+--+--+--+--+--+--+--+--+     |                |
 * | [H][H][H][H][H][H][H][H][H]      | [ 升级 ] Lv.N  |
 * +----------------------------------+----------------+
 * </pre>
 *
 * <p>关键: <b>imageWidth = this.width</b>, 让屏幕铺满整个游戏窗口宽度,
 * 这样 JEI (它会自己贴在屏幕最右侧 ~200px) 被挤出可见区域, 不会被 JEI 占空间.
 *
 * <p>右栏支持两个 tab:
 * <ul>
 *   <li><b>已连接</b>: 显示已通过容器连接器绑定的容器, 翻页/打开/编辑</li>
 *   <li><b>已收容</b>: 显示用实体收容器收的实体, 翻页/召唤/召回/编辑</li>
 * </ul>
 * 玩家走到世界任何位置都能打开已连接的容器, 没有任何距离限制.
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
    private static final int COLOR_TAB_ACTIVE = 0xFF_707070;
    private static final int COLOR_TAB_INACTIVE = 0xFF_505050;
    private static final int COLOR_ENTITY_GREEN = 0xFF_70_B870; // 已召唤 → 绿框

    // ===== 布局: 主区 =====
    /** 主区宽 (仓库 9x6 + 玩家物品栏 9x4 + 热栏 9x1) — 固定 9 列, 加上 padding. */
    private static final int MAIN_W = 9 * 18 + 16; // 178

    // ===== 布局: 已连接侧栏 (动态) =====
    /** 已连接侧栏的列数 (运行时按可用宽度算, 但有上限 — 太宽了图标摊一排看不清). */
    private int linkCols = 4;
    /** 已连接侧栏的行数 (每页). */
    private static final int LINK_ROWS = 7;
    /** 每页图标数 = linkCols * LINK_ROWS. */
    private int linksPerPage = linkCols * LINK_ROWS;
    /** 图标格子大小 (含 padding). */
    private static final int ICON_CELL = 22; // 18 + 4 padding, 略大更易看清
    /** 侧栏宽 (运行时: 整个屏幕宽 - 主区 - 间隔, 让侧栏铺满右半). */
    private int sideW = 88;
    /** 屏幕高. 240=仓库+物品栏+热栏+升级按钮+底部留白, 不重叠. */
    private static final int SCREEN_H = 246;
    /** linkCols 上限, 防止宽屏时图标只占 1-2 排, 看起来很散. */
    private static final int LINK_COLS_MAX = 8;

    // ===== 布局: 仓库右上分页 (主区分页, 仓库本体) =====
    private static final int PAGE_BTN_W = 14;
    private static final int PAGE_BTN_H = 14;
    /** 分页按钮顶部 Y 偏移, 比 titleLabelY 略低, 避免和左上角标题叠在一起. */
    private static final int PAGE_BTN_Y = 7;

    // ===== 布局: 已连接翻页按钮 (侧栏上方) =====
    private static final int SIDE_PAGE_BTN_W = 12;
    private static final int SIDE_PAGE_BTN_H = 12;
    /** 侧栏 tab 按钮高度. */
    private static final int TAB_H = 14;
    private static final int TAB_W = 56;
    /** 距离状态栏高度: "可打开 X / 不可打开 Y" 一行. */
    private static final int STATUS_LINE_H = 12;

    // ===== Tab 状态 =====
    /** 0 = 已连接 (容器), 1 = 已收容 (实体). */
    private int sideTab = 0;

    private final List<Rect> iconRects = new ArrayList<>();
    private final List<EntityRect> entityRects = new ArrayList<>();
    /** 已连接容器当前页 (0-based). */
    private int linkPage = 0;
    /** 已收容实体当前页 (0-based). */
    private int entityPage = 0;
    /**
     * 当前页 (可见的图标网格) 里, 玩家当前能打开 / 不能打开的容器数量.
     * 每次 renderContainerIcons 重算, 给状态栏显示用.
     * - openableCount: 同维度 + 在 maxSafeDistance 范围内 (普通容器永远算可打开)
     * - lockedCount: 跨维度 / 距离超出限制
     */
    private int openableCount = 0;
    private int lockedCount = 0;

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
        this.imageHeight = SCREEN_H;
        this.inventoryLabelY = WarehouseMenu.PLAYER_INV_Y - 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.imageWidth = this.width;
        this.leftPos = 0;
        this.topPos = (this.height - this.imageHeight) / 2;
        this.sideW = Math.max(140, this.width - MAIN_W - 8);
        // 列数: 按可用宽度算, 但封顶 8 (宽屏不至于一排 N 个)
        int colsByWidth = (this.sideW - 16) / ICON_CELL;
        this.linkCols = Math.max(2, Math.min(LINK_COLS_MAX, colsByWidth));
        this.linksPerPage = this.linkCols * LINK_ROWS;
        rebuildButtons();
    }

    @Override
    protected void rebuildWidgets() {
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
        int nextMainX = MAIN_W - PAGE_BTN_W - 4;
        int labelMainX = nextMainX - 32;
        int prevMainX = labelMainX - PAGE_BTN_W - 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (menu.currentPage() > 0) {
                PacketDistributor.sendToServer(new C2SSwitchWarehousePage(menu.currentPage() - 1));
            }
        }).bounds(leftPos + prevMainX, mainPageY, PAGE_BTN_W, PAGE_BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (menu.currentPage() < mainTotalPages - 1) {
                PacketDistributor.sendToServer(new C2SSwitchWarehousePage(menu.currentPage() + 1));
            }
        }).bounds(leftPos + nextMainX, mainPageY, PAGE_BTN_W, PAGE_BTN_H).build());
        this.mainLabelX = leftPos + labelMainX + 4;
        this.mainLabelY = mainPageY + 3;
        this.mainTotalPages = mainTotalPages;

        // ---- 侧栏 tab 切换 (容器 / 实体) ----
        int sideX = leftPos + MAIN_W + 8;
        int sideY = topPos + 4;
        int tabY = sideY;
        int tabContainerX = sideX;
        int tabEntityX = sideX + TAB_W + 4;
        addRenderableWidget(Button.builder(
            Component.literal("§f容器 [" + ClientWarehouseCache.getLinks().size() + "/" + maxContainersForLevel() + "]"),
            b -> { if (sideTab != 0) { sideTab = 0; rebuildButtons(); } })
            .bounds(tabContainerX, tabY, TAB_W, TAB_H).build());
        addRenderableWidget(Button.builder(
            Component.literal("§f实体 [" + ClientWarehouseCache.getEntityLinks().size() + "/" + maxEntitiesForLevel() + "]"),
            b -> { if (sideTab != 1) { sideTab = 1; rebuildButtons(); } })
            .bounds(tabEntityX, tabY, TAB_W, TAB_H).build());
        this.tabContainerX = tabContainerX;
        this.tabEntityX = tabEntityX;
        this.tabY = tabY;

        // ---- 侧栏翻页按钮 (放在 tab 下方) ----
        int sidePageY = tabY + TAB_H + 2;
        int sidePageTotal = totalSidePages();
        Button prevBtn = Button.builder(Component.literal("<"), b -> {
            if (sideTab == 0) {
                if (linkPage > 0) linkPage--;
            } else {
                if (entityPage > 0) entityPage--;
            }
            rebuildButtons();
        }).bounds(sideX, sidePageY, SIDE_PAGE_BTN_W, SIDE_PAGE_BTN_H).build();
        Button nextBtn = Button.builder(Component.literal(">"), b -> {
            if (sideTab == 0) {
                if (linkPage < sidePageTotal - 1) linkPage++;
            } else {
                if (entityPage < sidePageTotal - 1) entityPage++;
            }
            rebuildButtons();
        }).bounds(sideX + sideW - SIDE_PAGE_BTN_W, sidePageY, SIDE_PAGE_BTN_W, SIDE_PAGE_BTN_H).build();
        if (sidePageTotal > 0) {
            addRenderableWidget(prevBtn);
            addRenderableWidget(nextBtn);
        }
        this.sideLabelX = sideX + SIDE_PAGE_BTN_W + 4;
        this.sideLabelY = sidePageY + 2;
        this.sidePageTotal = sidePageTotal;
        // 距离状态栏: 翻页按钮下方, 紧挨着
        this.statusLineX = sideX + 4;
        this.statusLineY = sidePageY + SIDE_PAGE_BTN_H + 2;
        // 图标网格: 状态栏下方
        this.gridY = sidePageY + SIDE_PAGE_BTN_H + 4 + STATUS_LINE_H;

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
        gUpgradeLabelX = sideX + 4;
        gUpgradeLabelY = upgY + 5;
    }

    private int mainLabelX, mainLabelY;
    private int mainTotalPages;
    private int sideLabelX, sideLabelY;
    private int sidePageTotal;
    private int statusLineX, statusLineY;
    private int gUpgradeLabelX, gUpgradeLabelY;
    private int tabContainerX, tabEntityX, tabY;
    /** 网格 Y 起点 (侧栏翻页按钮下方). */
    private int gridY;

    private int totalSidePages() {
        if (sideTab == 0) {
            int n = ClientWarehouseCache.getLinks().size();
            return Math.max(1, (n + linksPerPage - 1) / linksPerPage);
        } else {
            int n = ClientWarehouseCache.getEntityLinks().size();
            return Math.max(1, (n + linksPerPage - 1) / linksPerPage);
        }
    }

    private int maxContainersForLevel() {
        int lv = ClientWarehouseCache.getUpgradeLevel();
        return com.example.mymod.warehouse.WarehouseLevel.maxContainersFor(lv);
    }

    private int maxEntitiesForLevel() {
        return EntityLink.maxEntityLinksFor(ClientWarehouseCache.getUpgradeLevel());
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COLOR_BG);
        g.fill(leftPos + 6, topPos + 14, leftPos + 6 + MAIN_W - 12, topPos + 14 + 108, COLOR_PANEL);
        int invPanelY = topPos + WarehouseMenu.PLAYER_INV_Y - 4;
        int invPanelH = (WarehouseMenu.HOTBAR_Y + 18) - (WarehouseMenu.PLAYER_INV_Y - 4);
        g.fill(leftPos + 6, invPanelY, leftPos + 6 + MAIN_W - 12, invPanelY + invPanelH, COLOR_PANEL);
        drawSlotGrid(g, WarehouseMenu.WAREHOUSE_X, WarehouseMenu.WAREHOUSE_Y, 9, 6);
        drawSlotGrid(g, WarehouseMenu.PLAYER_INV_X, WarehouseMenu.PLAYER_INV_Y, 9, 3);
        for (int col = 0; col < 9; col++) {
            drawSlot(g, leftPos + WarehouseMenu.PLAYER_INV_X + col * 18, topPos + WarehouseMenu.HOTBAR_Y);
        }
        int divX = leftPos + MAIN_W + 4;
        g.fill(divX, topPos + 6, divX + 2, topPos + imageHeight - 6, COLOR_DIVIDER);
        // 侧栏背景: 高度要包含 tab + 翻页按钮 + 状态栏 + 网格
        int sideX = leftPos + MAIN_W + 8;
        int sideY = topPos + 4;
        int sideH = TAB_H + 2 + SIDE_PAGE_BTN_H + 4 + STATUS_LINE_H + LINK_ROWS * ICON_CELL + 4;
        g.fill(sideX, sideY, sideX + sideW, sideY + sideH, COLOR_PANEL);
        // tab 边框: 高亮 active tab
        g.fill(tabContainerX - 1, tabY - 1, tabContainerX + TAB_W + 1, tabY + TAB_H + 1, COLOR_DIVIDER);
        g.fill(tabEntityX - 1, tabY - 1, tabEntityX + TAB_W + 1, tabY + TAB_H + 1, COLOR_DIVIDER);
        if (sideTab == 0) {
            g.fill(tabContainerX, tabY, tabContainerX + TAB_W, tabY + TAB_H, COLOR_TAB_ACTIVE);
        } else {
            g.fill(tabEntityX, tabY, tabEntityX + TAB_W, tabY + TAB_H, COLOR_TAB_ACTIVE);
        }
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
        entityRects.clear();
        // ---- hover tooltip (物品栏 / 云仓库 slot) ----
        // 显式渲染 slot item tooltip. AbstractContainerScreen.render 内部应该已经画了,
        // 但在某些情况下 (例如 imageWidth = this.width, leftPos = 0) 会因为布局异常
        // 导致 super 的 tooltip 渲染没触发, 这里兜底, 确保物品悬停有信息.
        if (this.hoveredSlot != null && this.hoveredSlot.isActive() && this.hoveredSlot.hasItem()) {
            ItemStack hovered = this.hoveredSlot.getItem();
            if (!hovered.isEmpty()) {
                g.renderTooltip(font, hovered, mouseX, mouseY);
            }
        }
        // 主区分页标签
        g.drawString(font, (menu.currentPage() + 1) + "/" + mainTotalPages,
            mainLabelX, mainLabelY, COLOR_TEXT, false);
        // 侧栏分页标签
        g.drawString(font, currentSidePage() + "/" + sidePageTotal, sideLabelX, sideLabelY, COLOR_TEXT, false);
        // 等级标签 (侧栏底部, 升级按钮左边)
        g.drawString(font, "Lv." + ClientWarehouseCache.getUpgradeLevel(),
            gUpgradeLabelX, gUpgradeLabelY, COLOR_TEXT, false);

        int sideX = leftPos + MAIN_W + 8;
        // ---- 图标网格: 按当前 tab ----
        if (sideTab == 0) {
            renderContainerIcons(g, sideX, mouseX, mouseY);
            // 距离状态栏: 仅"容器"tab 显示 (实体没距离限制)
            g.drawString(font,
                "§a✔ 可打开 " + openableCount + " §7| §c✘ 不可 " + lockedCount,
                statusLineX, statusLineY, COLOR_TEXT, false);
        } else {
            renderEntityIcons(g, sideX, mouseX, mouseY);
        }

        // ---- hover tooltip ----
        if (sideTab == 0) {
            for (Rect r : iconRects) {
                if (r.contains(mouseX, mouseY)) {
                    // 先按基础 tooltip 拿所有行, 再按距离/维度追加实时信息
                    List<Component> allLines = new ArrayList<>(r.link.toTooltip());
                    if (!r.sameDim()) {
                        allLines.add(Component.literal("§c[其他维度, 无法在此处打开]"));
                    } else if (r.distance() >= 0) {
                        r.link.appendDistanceInfo(allLines, r.distance());
                    }
                    List<FormattedCharSequence> tipLines = new ArrayList<>();
                    for (Component c : allLines) {
                        tipLines.add(c.getVisualOrderText());
                    }
                    g.renderTooltip(font, tipLines, mouseX, mouseY);
                    break;
                }
            }
        } else {
            for (EntityRect r : entityRects) {
                if (r.contains(mouseX, mouseY)) {
                    List<FormattedCharSequence> tipLines = new ArrayList<>();
                    for (Component c : r.link.toTooltip()) {
                        tipLines.add(c.getVisualOrderText());
                    }
                    g.renderTooltip(font, tipLines, mouseX, mouseY);
                    break;
                }
            }
        }
    }

    private int currentSidePage() {
        return (sideTab == 0 ? linkPage : entityPage) + 1;
    }

    private void renderContainerIcons(GuiGraphics g, int sideX, int mouseX, int mouseY) {
        var links = ClientWarehouseCache.getLinks();
        int startIdx = linkPage * linksPerPage;
        int endIdx = Math.min(links.size(), startIdx + linksPerPage);
        int gridX = sideX + 4;

        // 拿到玩家当前位置 + 维度, 用来算每个容器的距离
        Player player = (minecraft != null) ? minecraft.player : null;
        Level playerLevel = (player != null) ? player.level() : null;
        // ResourceKey.equals 已经按 location 比, 直接比即可
        boolean playerHasLevel = (player != null && playerLevel != null);

        int openable = 0;
        int locked = 0;

        for (int i = startIdx; i < endIdx; i++) {
            var link = links.get(i);
            int idx = i - startIdx;
            int col = idx % linkCols;
            int row = idx / linkCols;
            int bx = gridX + col * ICON_CELL;
            int by = gridY + row * ICON_CELL;

            // ---- 距离 + 范围判定 ----
            // - 跨维度: 一律算"无法打开" (本 mod 不支持跨维度, 跟 C2SOpenLinkedContainer 保持一致)
            // - 同维度: 算距离, 高级容器超 maxSafeDistance 算"无法打开", 普通容器永远可打开
            int distance = -1;
            boolean sameDim = false;
            boolean inRange = true;
            if (playerHasLevel) {
                sameDim = link.dimension().equals(playerLevel.dimension());
                if (sameDim) {
                    distance = (int) Math.sqrt(player.blockPosition().distSqr(link.pos()));
                    inRange = (link.maxSafeDistance() == 0) || (distance <= link.maxSafeDistance());
                } else {
                    inRange = false;
                }
            }
            if (inRange) openable++; else locked++;

            Rect rect = new Rect(bx, by, ICON_CELL - 2, ICON_CELL - 2, link, distance, inRange, sameDim);
            iconRects.add(rect);
            boolean hover = rect.contains(mouseX, mouseY);

            // 背景
            g.fill(bx, by, bx + ICON_CELL - 2, by + ICON_CELL - 2,
                hover ? COLOR_BTN_HOVER : COLOR_BTN_NORMAL);
            // 边框: 不可打开的容器用红色边框, 一眼能看出来
            int borderColor = inRange ? 0xFF_404040 : 0xFF_B03030;
            g.fill(bx, by, bx + ICON_CELL - 2, by + 1, borderColor);
            g.fill(bx, by + ICON_CELL - 3, bx + ICON_CELL - 2, by + ICON_CELL - 2, borderColor);
            g.fill(bx, by, bx + 1, by + ICON_CELL - 2, borderColor);
            g.fill(bx + ICON_CELL - 3, by, bx + ICON_CELL - 2, by + ICON_CELL - 2, borderColor);
            // 图标
            ItemStack icon = blockIdToStack(link.blockId());
            g.renderItem(icon, bx + (ICON_CELL - 2 - 16) / 2, by + (ICON_CELL - 2 - 16) / 2);

            // 不可打开: 整格半透明红黑覆盖 + 中央大红 ✘ (不能再忽略)
            if (!inRange) {
                g.fill(bx, by, bx + ICON_CELL - 2, by + ICON_CELL - 2, 0x88_400000);
                // 居中的大 ✘ (8 像素高, 居中绘制)
                String mark = "§4§l✘";
                int markW = font.width(mark);
                g.drawString(font, mark,
                    bx + (ICON_CELL - 2 - markW) / 2,
                    by + (ICON_CELL - 2 - 8) / 2,
                    0xFF_FFFFFF, true);
            }
        }

        // 给状态栏用
        this.openableCount = openable;
        this.lockedCount = locked;
    }

    private void renderEntityIcons(GuiGraphics g, int sideX, int mouseX, int mouseY) {
        var entities = ClientWarehouseCache.getEntityLinks();
        int startIdx = entityPage * linksPerPage;
        int endIdx = Math.min(entities.size(), startIdx + linksPerPage);
        int gridX = sideX + 4;
        for (int i = startIdx; i < endIdx; i++) {
            var link = entities.get(i);
            int idx = i - startIdx;
            int col = idx % linkCols;
            int row = idx / linkCols;
            int bx = gridX + col * ICON_CELL;
            int by = gridY + row * ICON_CELL;
            EntityRect rect = new EntityRect(bx, by, ICON_CELL - 2, ICON_CELL - 2, link);
            entityRects.add(rect);
            boolean hover = rect.contains(mouseX, mouseY);
            int bg = hover ? COLOR_BTN_HOVER : COLOR_BTN_NORMAL;
            g.fill(bx, by, bx + ICON_CELL - 2, by + ICON_CELL - 2, bg);
            int border = link.summoned() ? COLOR_ENTITY_GREEN : 0xFF_404040;
            g.fill(bx, by, bx + ICON_CELL - 2, by + 1, border);
            g.fill(bx, by + ICON_CELL - 3, bx + ICON_CELL - 2, by + ICON_CELL - 2, border);
            g.fill(bx, by, bx + 1, by + ICON_CELL - 2, border);
            g.fill(bx + ICON_CELL - 3, by, bx + ICON_CELL - 2, by + ICON_CELL - 2, border);
            // 图标: 优先 spawn egg, 没有就用 ender_pearl
            ItemStack icon = entityIdToStack(link.entityTypeId());
            g.renderItem(icon, bx + (ICON_CELL - 2 - 16) / 2, by + (ICON_CELL - 2 - 16) / 2);
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

    private static ItemStack entityIdToStack(String entityTypeId) {
        return com.example.mymod.warehouse.EntityIconUtil.iconFor(entityTypeId);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sideTab == 0) {
            for (Rect r : iconRects) {
                if (r.contains((int) mouseX, (int) mouseY)) {
                    if (button == 1) {
                        ExampleMod.LOGGER.info("[Warehouse] right-click on container icon {}", r.link.linkId());
                        PacketDistributor.sendToServer(new C2SRequestEditLinked(r.link.linkId()));
                    } else {
                        ExampleMod.LOGGER.info("[Warehouse] left-click on container icon {}", r.link.linkId());
                        pendingReturn = this;
                        PacketDistributor.sendToServer(new C2SOpenLinkedContainer(r.link.linkId()));
                    }
                    return true;
                }
            }
        } else {
            for (EntityRect r : entityRects) {
                if (r.contains((int) mouseX, (int) mouseY)) {
                    ExampleMod.LOGGER.info("[Warehouse] click on entity icon: button={} linkId={} type={}",
                        button, r.link.linkId(), r.link.entityTypeId());
                    if (button == 1) {
                        // 右键: 编辑 (改名 + 解除)
                        ExampleMod.LOGGER.info("[Warehouse] sending C2SOpenEntityEdit for {}", r.link.linkId());
                        PacketDistributor.sendToServer(new C2SOpenEntityEdit(r.link.linkId()));
                    } else {
                        // 左键: 弹出快捷操作 (召唤 / 召回)
                        ExampleMod.LOGGER.info("[Warehouse] opening EntityActionPopup for {}", r.link.linkId());
                        EntityActionPopup.open(
                            r.link.linkId(),
                            r.link.getDisplayName().getString(),
                            entityIdToStack(r.link.entityTypeId()),
                            r.link.entityTypeId(),
                            r.link.summoned(),
                            this);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private record Rect(int x, int y, int w, int h, LinkedContainer link,
                        int distance, boolean inRange, boolean sameDim) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record EntityRect(int x, int y, int w, int h, EntityLink link) {
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}

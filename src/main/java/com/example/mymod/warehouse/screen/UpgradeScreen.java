package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.WarehouseLevel;
import com.example.mymod.warehouse.client.ClientWarehouseCache;
import com.example.mymod.warehouse.network.C2SSubmitUpgradeMaterials;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仓库升级界面 —— LDLib2 重写.
 * <p>
 * 布局 (单列垂直, 全部居中, 固定宽度避免溢出):
 * <pre>
 *       ┌────────────────────────────────────┐
 *       │         云存储 - 升级              │
 *       │  ┌──────────────────────────────┐  │
 *       │  │ 当前等级 Lv.1                │  │
 *       │  │   容器上限: 5 个             │  │
 *       │  │   仓库页数: 1 页 (54 格)     │  │
 *       │  └──────────────────────────────┘  │
 *       │  ┌──────────────────────────────┐  │
 *       │  │ 下一级 Lv.2                  │  │
 *       │  │   容器上限: 10 个            │  │
 *       │  │   仓库页数: 5 页 (270 格)    │  │
 *       │  └──────────────────────────────┘  │
 *       │  ┌──────────────────────────────┐  │
 *       │  │ 升级材料                     │  │
 *       │  │ [□] 箱子  ████░░░░ 4/10       │  │
 *       │  │ [□] 铁锭  ████████ 32/32     │  │
 *       │  └──────────────────────────────┘  │
 *       │      [ 提交材料 ]  [ 取消 ]         │
 *       └────────────────────────────────────┘
 * </pre>
 */
public final class UpgradeScreen {
    /** 根容器宽度. 留大点保证材料行的进度条够长, 但不能超过屏幕 854px. */
    private static final int ROOT_W = 400;
    /** 根容器高度. */
    private static final int ROOT_H = 300;

    /**
     * 打开本界面时, 把当前屏幕 (一般是仓库主界面) 记到这里. 用户点取消/提交后
     * 关闭本界面, 调 {@link #goBack(Minecraft)} 回到这里记的屏幕, 而不是直接关游戏.
     */
    private static Screen parent;

    private UpgradeScreen() {}

    public static void open() {
        open(Minecraft.getInstance().screen);
    }

    public static void open(Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        ModularUI modularUI = buildUI(mc);
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("gui." + ExampleMod.MOD_ID + ".upgrade.title")) {
            @Override
            public void onClose() {
                // ESC 关闭时也要回到 parent (即升级前的 WarehouseScreen),
                // 否则 parent 被丢, 玩家要再按 V 才能看到新页数.
                UpgradeScreen.goBack(mc);
            }
        });
    }

    /** 关掉自己, 回到 {@link #parent} (如果还在, 否则关到根). */
    private static void goBack(Minecraft mc) {
        if (parent != null) {
            mc.setScreen(parent);
            // 双保险: setScreen(parent) 会触发 parent.init() → rebuildWidgets,
            // 但有些版本可能跳过, 这里再强制调一次, 确保升级后的新页数立刻显示.
            if (parent instanceof com.example.mymod.warehouse.screen.WarehouseScreen ws) {
                ws.refreshWidgets();
            }
            parent = null;
        } else {
            mc.setScreen(null);
        }
    }

    private static ModularUI buildUI(Minecraft mc) {
        int cur = ClientWarehouseCache.getUpgradeLevel();
        WarehouseLevel curDef = WarehouseLevel.of(cur);
        WarehouseLevel next = curDef.next();
        Map<Item, Integer> progress = ClientWarehouseCache.getUpgradeProgress();
        if (progress == null) progress = new LinkedHashMap<>();
        boolean isMax = (next == null);

        // ---- 根容器: 用 alignSelf CENTER 让它在父容器里居中 ----
        UIElement root = new UIElement();
        root.setId("upgrade-root");
        root.layout(l -> l
            .width(ROOT_W).height(ROOT_H)
            .paddingAll(10)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)  // 水平居中子元素
            .gapAll(6));
        root.style(s -> s.background(Sprites.BORDER));

        // 标题
        Label title = new Label();
        title.setText(Component.literal("云存储 - 升级"));
        title.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER).textShadow(true));
        title.layout(l -> l.height(14).width(ROOT_W - 20));
        root.addChild(title);

        // 当前等级 section (宽度 = 根宽度 - 20)
        root.addChild(buildLevelSection(
            "当前等级 Lv." + cur,
            curDef.maxLinkedContainers,
            curDef.warehousePages
        ));

        // 下一级 section
        if (isMax) {
            UIElement maxSection = buildSection("下一级 (满级)");
            maxSection.addChild(makeLabel("§a已满级 — 100 个 / 100 页 (5400 格)"));
            root.addChild(maxSection);
        } else {
            root.addChild(buildLevelSection(
                "下一级 Lv." + next.level,
                next.maxLinkedContainers,
                next.warehousePages
            ));
        }

        // 升级材料 section
        root.addChild(buildMaterialsSection(next, progress, isMax));

        // 按钮行
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .width(ROOT_W - 20).height(22)
            .justifyContent(AlignContent.CENTER)
            .alignItems(AlignItems.CENTER)
            .gapAll(16)
            .marginTop(4));
        Button submit = new Button();
        submit.setText(isMax ? "已满级" : "提交材料");
        submit.layout(l -> l.width(90).height(20));
        if (!isMax) {
            submit.setOnClick(e -> {
                PacketDistributor.sendToServer(new C2SSubmitUpgradeMaterials());
                goBack(mc);
            });
        }
        btnRow.addChild(submit);

        Button cancel = new Button();
        cancel.setText("取消");
        cancel.layout(l -> l.width(90).height(20));
        cancel.setOnClick(e -> goBack(mc));
        btnRow.addChild(cancel);

        root.addChild(btnRow);

        return ModularUI.of(UI.of(root), mc.player);
    }

    /**
     * 通用标签.
     */
    private static Label makeLabel(String text) {
        Label label = new Label();
        label.setText(Component.literal(text));
        label.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        label.layout(l -> l.height(12).width(ROOT_W - 32));
        return label;
    }

    /**
     * 一段浅色背景的 section, 自动加子元素垂直堆叠.
     */
    private static UIElement buildSection(String title) {
        UIElement section = new UIElement();
        section.layout(l -> l
            .width(ROOT_W - 20)
            .flexDirection(FlexDirection.COLUMN)
            .paddingAll(6)
            .gapAll(2));
        section.style(s -> s.background(Sprites.RECT));
        section.addChild(makeLabel(title).textStyle(s -> s.textShadow(true)));
        return section;
    }

    /**
     * 当前/下一级 section: 标题 + 容器上限 + 仓库页数.
     */
    private static UIElement buildLevelSection(String title, int maxContainers, int pages) {
        UIElement section = buildSection(title);
        section.addChild(makeLabel("容器上限: " + maxContainers + " 个"));
        section.addChild(makeLabel("仓库页数: " + pages + " 页 ("
            + (pages * 54) + " 格)"));
        return section;
    }

    /**
     * 升级材料 section: 整列宽, 每个材料一行 [图标] [名字] [进度条] [数字].
     */
    private static UIElement buildMaterialsSection(WarehouseLevel next, Map<Item, Integer> progress, boolean isMax) {
        UIElement section = buildSection("升级材料");
        if (isMax) {
            section.addChild(makeLabel("§7已满级, 无需升级材料"));
            return section;
        }
        for (var e : next.requirements.entrySet()) {
            Item item = e.getKey();
            int need = e.getValue();
            int have = Math.min(need, progress.getOrDefault(item, 0));
            section.addChild(buildMaterialRow(item, have, need));
        }
        return section;
    }

    /**
     * 一个材料进度行: 图标 + 名字 + 进度条 + 数字.
     * <p>
     * 关键: 进度条用 <b>固定宽度</b> 150px, 不要 flex(1),
     * 否则 LDLib2 的 flex 布局会让它撑爆 section, 出现溢出.
     */
    private static UIElement buildMaterialRow(Item item, int have, int need) {
        UIElement row = new UIElement();
        row.layout(l -> l
            .width(ROOT_W - 32)
            .height(18)
            .flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER)
            .gapAll(4));
        // 物品图标
        ItemStack stack = new ItemStack(item);
        UIElement icon = new UIElement();
        icon.layout(l -> l.width(16).height(16));
        icon.style(s -> s.backgroundTexture(new ItemStackTexture(stack)));
        row.addChild(icon);
        // 名字
        Label nameLabel = new Label();
        nameLabel.setText(stack.getHoverName());
        nameLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        nameLabel.layout(l -> l.width(50).height(12));
        row.addChild(nameLabel);
        // 进度条 (固定宽度 150px, 不 flex)
        ProgressBar bar = new ProgressBar();
        bar.setRange(0, Math.max(1, need));
        bar.setProgress(have);
        bar.layout(l -> l.width(150).height(12));
        row.addChild(bar);
        // 数字
        Label countLabel = new Label();
        countLabel.setText(Component.literal(have + "/" + need));
        countLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.RIGHT));
        countLabel.layout(l -> l.width(40).height(12));
        row.addChild(countLabel);
        return row;
    }
}

package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.client.ClientWarehouseCache;
import com.example.mymod.warehouse.network.C2SSubmitContainerName;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 命名存储容器界面 —— 用 LDLib2 重写, 解决 Minecraft 自带字体在 854x480 小屏下糊的问题.
 * <p>
 * 布局:
 * <pre>
 * +--------------------------------------+
 * |         命名存储容器                   |
 * |                                      |
 * |  容量: 27 格                          |
 * |                                      |
 * |  名字:  [_________________]          |
 * |                                      |
 * |  留空使用默认名                       |
 * |                                      |
 * |            [ 保存 ]   [ 取消 ]        |
 * +--------------------------------------+
 * </pre>
 *
 * 流程: 服务端发 {@link com.example.mymod.warehouse.network.S2COpenNameEntry} →
 * 客户端调 {@link #open(UUID, String, ItemStack, int)} → LDLib2 ModularUI 接管渲染 →
 * 点保存发 {@link C2SSubmitContainerName} → 服务端写回 link.name.
 */
public final class NameEntryScreen {
    /**
     * 打开本界面时, 把当前屏幕 (一般是仓库主界面) 记到这里. 用户点取消/保存后
     * 关闭本界面, 调 {@link #goBack(Minecraft)} 回到这里记的屏幕, 而不是直接关游戏.
     */
    private static Screen parent;

    private NameEntryScreen() {}

    public static void open(UUID linkId, String defaultName, ItemStack iconStack, int slots) {
        open(linkId, defaultName, iconStack, slots, Minecraft.getInstance().screen);
    }

    public static void open(UUID linkId, String defaultName, ItemStack iconStack, int slots, Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        ModularUI modularUI = buildUI(linkId, defaultName, slots, mc);
        // 标题用 Component.literal 让 LDLib2 显示在屏幕标题栏
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("container." + ExampleMod.MOD_ID + ".name_entry.title")));
    }

    /** 关掉自己, 回到 {@link #parent} (如果还在, 否则关到根). */
    private static void goBack(Minecraft mc) {
        if (parent != null) {
            mc.setScreen(parent);
            parent = null;
        } else {
            mc.setScreen(null);
        }
    }

    private static ModularUI buildUI(UUID linkId, String defaultName, int slots, Minecraft mc) {
        // 根容器: 居中漂浮 (zIndex 提到顶层), 限制宽高, 白色背景
        UIElement root = new UIElement();
        root.setId("name-entry-root");
        root.layout(l -> l
            .width(320).height(190)
            .paddingAll(14)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        root.style(s -> s.background(Sprites.BORDER)); // 浅灰 GDP 边框

        // 标题
        Label title = new Label();
        title.setText(Component.literal("命名存储容器"));
        title.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER).textShadow(true));
        title.layout(l -> l.height(14).widthPercent(100));
        root.addChild(title);

        // 容量描述
        Label cap = new Label();
        cap.setText(Component.literal("容量: " + slots + " 格"));
        cap.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        cap.layout(l -> l.height(12).widthPercent(100));
        root.addChild(cap);

        // 名字输入行 (Label + TextField)
        UIElement nameRow = new UIElement();
        nameRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(20)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        Label nameLabel = new Label();
        nameLabel.setText(Component.literal("名字:"));
        nameLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        nameLabel.layout(l -> l.width(40).height(14));
        nameRow.addChild(nameLabel);
        TextField nameField = new TextField();
        nameField.setValue((defaultName == null) ? "" : defaultName, false);
        nameField.setTextResponder(s -> { /* 实时存储, 这里不处理, 保存时取 */ });
        nameField.layout(l -> l.flex(1).height(18));
        nameRow.addChild(nameField);
        root.addChild(nameRow);

        // 提示
        Label hint = new Label();
        hint.setText(Component.literal("留空使用默认名"));
        hint.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        hint.layout(l -> l.height(10).widthPercent(100));
        root.addChild(hint);

        // 按钮行
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .justifyContent(AlignContent.CENTER)
            .gapAll(12)
            .marginTop(6));
        Button saveBtn = new Button();
        saveBtn.setText("保存");
        saveBtn.layout(l -> l.width(80).height(20));
        saveBtn.setOnClick(e -> {
            String v = nameField.getValue();
            if (v == null) v = (defaultName == null) ? "" : defaultName;
            PacketDistributor.sendToServer(new C2SSubmitContainerName(linkId, v));
            ClientWarehouseCache.clearPending();
            // 保存后直接关闭屏幕, 回到上级 (一般是仓库主界面)
            goBack(mc);
        });
        btnRow.addChild(saveBtn);

        Button cancelBtn = new Button();
        cancelBtn.setText("取消");
        cancelBtn.layout(l -> l.width(80).height(20));
        cancelBtn.setOnClick(e -> {
            ClientWarehouseCache.clearPending();
            goBack(mc);
        });
        btnRow.addChild(cancelBtn);

        root.addChild(btnRow);

        return ModularUI.of(UI.of(root), mc.player);
    }
}

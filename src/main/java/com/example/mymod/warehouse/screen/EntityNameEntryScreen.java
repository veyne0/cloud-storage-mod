package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.network.C2SSaveEntityName;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
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
 * 收容实体后的首次命名界面 —— 用 LDLib2 重写.
 * <p>
 * 流程: 玩家手持实体收容器右键实体 →
 * {@link com.example.mymod.warehouse.network.C2SCaptureEntity} →
 * 服务端收容 + 发 {@link com.example.mymod.warehouse.network.S2COpenEntityNameEntry} →
 * 客户端调 {@link #open(UUID, String, ItemStack)} → LDLib2 ModularUI 接管渲染 →
 * 点保存发 {@link C2SSaveEntityName} (这里用 defaultName 作为初始值).
 * <p>
 * 布局 (与 {@link NameEntryScreen} 类似, 但加 icon + 容量提示):
 * <pre>
 * +--------------------------------------+
 * |        命名收容的实体                  |
 * |  [icon]   §7类型: minecraft:cow      |
 * |                                      |
 * |  名字:  [_________________]          |
 * |                                      |
 * |  §7留空使用默认名                     |
 * |                                      |
 * |            [ 保存 ]   [ 取消 ]        |
 * +--------------------------------------+
 * </pre>
 */
public final class EntityNameEntryScreen {
    private static Screen parent;

    private EntityNameEntryScreen() {}

    public static void open(UUID linkId, String defaultName, ItemStack iconStack) {
        open(linkId, defaultName, iconStack, Minecraft.getInstance().screen);
    }

    public static void open(UUID linkId, String defaultName, ItemStack iconStack, Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        com.example.mymod.ExampleMod.LOGGER.info(
            "[EntityNameEntry] open: linkId={} defaultName={} parent={}",
            linkId, defaultName, parentScreen == null ? "null" : parentScreen.getClass().getSimpleName());
        ModularUI modularUI = buildUI(linkId, defaultName, iconStack, mc);
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("container." + ExampleMod.MOD_ID + ".entity_name_entry.title")));
    }

    private static void goBack(Minecraft mc) {
        if (parent != null) {
            mc.setScreen(parent);
            parent = null;
        } else {
            mc.setScreen(null);
        }
    }

    private static ModularUI buildUI(UUID linkId, String defaultName, ItemStack iconStack, Minecraft mc) {
        UIElement root = new UIElement();
        root.setId("entity-name-entry-root");
        root.layout(l -> l
            .width(320).height(180)
            .paddingAll(14)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        root.style(s -> s.background(Sprites.BORDER));

        // 标题
        Label title = new Label();
        title.setText(Component.translatable("container." + ExampleMod.MOD_ID + ".entity_name_entry.title"));
        title.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER).textShadow(true));
        title.layout(l -> l.height(14).widthPercent(100));
        root.addChild(title);

        // 信息行: 图标 + 默认名
        UIElement infoRow = new UIElement();
        infoRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(28)
            .alignItems(AlignItems.CENTER)
            .gapAll(10));
        UIElement iconBox = new UIElement();
        iconBox.layout(l -> l.width(24).height(24));
        iconBox.style(s -> s.background(new ItemStackTexture(iconStack)));
        infoRow.addChild(iconBox);
        Label nameLabel = new Label();
        nameLabel.setText(Component.literal("§7默认: " + (defaultName == null ? "" : defaultName)));
        nameLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        nameLabel.layout(l -> l.flex(1).height(14));
        infoRow.addChild(nameLabel);
        root.addChild(infoRow);

        // 名字输入行
        UIElement nameRow = new UIElement();
        nameRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(20)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        Label nameLabel2 = new Label();
        nameLabel2.setText(Component.literal("名字:"));
        nameLabel2.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        nameLabel2.layout(l -> l.width(40).height(14));
        nameRow.addChild(nameLabel2);
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
            // 把名字写回 link (即使是空, 服务端会 fallback 到实体类型名)
            PacketDistributor.sendToServer(new C2SSaveEntityName(linkId, v));
            // 清掉 pending
            com.example.mymod.warehouse.client.ClientWarehouseCache.clearPendingEntityName();
            goBack(mc);
        });
        btnRow.addChild(saveBtn);

        Button cancelBtn = new Button();
        cancelBtn.setText("取消");
        cancelBtn.layout(l -> l.width(80).height(20));
        cancelBtn.setOnClick(e -> {
            com.example.mymod.warehouse.client.ClientWarehouseCache.clearPendingEntityName();
            goBack(mc);
        });
        btnRow.addChild(cancelBtn);

        root.addChild(btnRow);

        return ModularUI.of(UI.of(root), mc.player);
    }
}

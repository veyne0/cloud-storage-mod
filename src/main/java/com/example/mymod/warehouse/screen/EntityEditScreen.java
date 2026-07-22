package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.client.ClientWarehouseCache;
import com.example.mymod.warehouse.network.C2SRecallEntity;
import com.example.mymod.warehouse.network.C2SSaveEntityName;
import com.example.mymod.warehouse.network.C2SUnlinkEntity;
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
 * 编辑已收容的实体 —— 用 LDLib2 重写.
 * <p>
 * 布局:
 * <pre>
 * +--------------------------------------+
 * |       编辑收容的实体                   |
 * |  [icon]   类型: minecraft:cow        |
 * |           状态: §7存储中 / §a已召唤   |
 * |                                      |
 * |  名字: [_________________]           |
 * |  §7留空使用实体类型名                 |
 * |                                      |
 * |  [ 召回 ]  [ 解除收容 ]  [ 取消 ]    |
 * +--------------------------------------+
 * </pre>
 * <p>
 * 流程: 玩家在仓库 UI 右键实体图标 →
 * {@link com.example.mymod.warehouse.network.C2SOpenEntityEdit} →
 * 服务端发 {@link com.example.mymod.warehouse.network.S2COpenEntityEdit} →
 * 客户端调 {@link #open(UUID, String, ItemStack, String, boolean)} →
 * LDLib2 ModularUI 接管渲染.
 * <p>
 * "召回" 用 {@link C2SRecallEntity} (已召唤 → 收回云端);
 * "解除收容" 用 {@link C2SUnlinkEntity} (服务端会先把实体召唤出来, 再删除 link).
 */
public final class EntityEditScreen {
    /**
     * 打开本界面时, 把当前屏幕 (一般是仓库主界面) 记到这里. 用户点取消/召回/解除后
     * 关闭本界面, 调 {@link #goBack(Minecraft)} 回到这里记的屏幕.
     */
    private static Screen parent;

    private EntityEditScreen() {}

    public static void open(UUID linkId, String currentName, ItemStack iconStack, String entityTypeId, boolean summoned) {
        open(linkId, currentName, iconStack, entityTypeId, summoned, Minecraft.getInstance().screen);
    }

    public static void open(UUID linkId, String currentName, ItemStack iconStack, String entityTypeId,
                            boolean summoned, Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        com.example.mymod.ExampleMod.LOGGER.info(
            "[EntityEdit] open: linkId={} name={} type={} summoned={} parent={}",
            linkId, currentName, entityTypeId, summoned,
            parentScreen == null ? "null" : parentScreen.getClass().getSimpleName());
        ModularUI modularUI = buildUI(linkId, currentName, iconStack, entityTypeId, summoned, mc);
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("container." + ExampleMod.MOD_ID + ".entity_edit.title")));
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

    private static ModularUI buildUI(UUID linkId, String currentName, ItemStack iconStack,
                                     String entityTypeId, boolean summoned, Minecraft mc) {
        // 根容器: 居中漂浮, 限制宽高
        UIElement root = new UIElement();
        root.setId("entity-edit-root");
        root.layout(l -> l
            .width(360).height(230)
            .paddingAll(14)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        root.style(s -> s.background(Sprites.BORDER));

        // 标题
        Label title = new Label();
        title.setText(Component.translatable("container." + ExampleMod.MOD_ID + ".entity_edit.title"));
        title.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER).textShadow(true));
        title.layout(l -> l.height(14).widthPercent(100));
        root.addChild(title);

        // 信息行: 图标 + 类型 + 状态
        UIElement infoRow = new UIElement();
        infoRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(28)
            .alignItems(AlignItems.CENTER)
            .gapAll(10));

        // 图标: 用 ItemStackTexture
        UIElement iconBox = new UIElement();
        iconBox.layout(l -> l.width(24).height(24));
        iconBox.style(s -> s.background(new ItemStackTexture(iconStack)));
        infoRow.addChild(iconBox);

        // 类型 + 状态文字
        UIElement textCol = new UIElement();
        textCol.layout(l -> l
            .flexDirection(FlexDirection.COLUMN)
            .flex(1).height(28)
            .justifyContent(AlignContent.CENTER));
        Label typeLabel = new Label();
        typeLabel.setText(Component.literal("§7类型: " + entityTypeId));
        typeLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        typeLabel.layout(l -> l.height(12).widthPercent(100));
        textCol.addChild(typeLabel);

        Label statusLabel = new Label();
        statusLabel.setText(summoned
            ? Component.literal("§a已召唤到世界")
            : Component.literal("§7存储在云端"));
        statusLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        statusLabel.layout(l -> l.height(12).widthPercent(100));
        textCol.addChild(statusLabel);

        infoRow.addChild(textCol);
        root.addChild(infoRow);

        // 名字输入行
        UIElement nameRow = new UIElement();
        nameRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(20)
            .alignItems(AlignItems.CENTER)
            .gapAll(8)
            .marginTop(4));
        Label nameLabel = new Label();
        nameLabel.setText(Component.literal("§f名字:"));
        nameLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT));
        nameLabel.layout(l -> l.width(40).height(14));
        nameRow.addChild(nameLabel);
        TextField nameField = new TextField();
        nameField.setValue((currentName == null) ? "" : currentName, false);
        nameField.setTextResponder(s -> { /* 实时存储, 这里不处理, 保存时取 */ });
        nameField.layout(l -> l.flex(1).height(18));
        nameRow.addChild(nameField);
        root.addChild(nameRow);

        // 提示
        Label hint = new Label();
        hint.setText(Component.literal("§7留空使用实体类型名, 点保存后生效"));
        hint.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        hint.layout(l -> l.height(10).widthPercent(100));
        root.addChild(hint);

        // 按钮行: 召回 / 解除 / 取消
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .justifyContent(AlignContent.CENTER)
            .gapAll(10)
            .marginTop(6));

        Button recallBtn = new Button();
        recallBtn.setText(summoned ? "§e召回" : "§7召回 (需先召唤)");
        recallBtn.layout(l -> l.width(80).height(20));
        recallBtn.setOnClick(e -> {
            if (!summoned) return; // 没召唤过, 不能召回
            PacketDistributor.sendToServer(new C2SRecallEntity(linkId));
            goBack(mc);
        });
        btnRow.addChild(recallBtn);

        Button unlinkBtn = new Button();
        unlinkBtn.setText("§c解除收容");
        unlinkBtn.layout(l -> l.width(90).height(20));
        unlinkBtn.setOnClick(e -> {
            PacketDistributor.sendToServer(new C2SUnlinkEntity(linkId));
            goBack(mc);
        });
        btnRow.addChild(unlinkBtn);

        Button cancelBtn = new Button();
        cancelBtn.setText("取消");
        cancelBtn.layout(l -> l.width(60).height(20));
        cancelBtn.setOnClick(e -> goBack(mc));
        btnRow.addChild(cancelBtn);

        root.addChild(btnRow);

        return ModularUI.of(UI.of(root), mc.player);
    }
}

package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.network.C2SSubmitContainerName;
import com.example.mymod.warehouse.network.C2SUnlinkLinkedContainer;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 编辑已连接容器界面 —— 用 LDLib2 重写, 解决 Minecraft 自带字体在 854x480 小屏下糊的问题.
 * <p>
 * 布局:
 * <pre>
 * +--------------------------------------+
 * |       编辑已连接容器                   |
 * |                                      |
 * |  类型: minecraft:chest  容量: 27 格   |
 * |  位置: 维度 overworld (10, 64, -3)   |
 * |                                      |
 * |  名字: [_________________]           |
 * |                                      |
 * |  [ 保存名字 ]  [ 解除连接 ]  [ 取消 ] |
 * +--------------------------------------+
 * </pre>
 *
 * 流程: 玩家在仓库 UI 右键图标 → {@link com.example.mymod.warehouse.network.C2SRequestEditLinked}
 * → 服务端发 {@link com.example.mymod.warehouse.network.S2COpenEditLinked} → 客户端调
 * {@link #open(UUID, String, String, int, String, int, int, int)} → LDLib2 ModularUI 接管渲染.
 * <p>
 * "保存名字" 复用 {@link C2SSubmitContainerName}, "解除连接" 用
 * {@link C2SUnlinkLinkedContainer}.
 */
public final class EditLinkedScreen {
    /**
     * 打开本界面时, 把当前屏幕 (一般是仓库主界面) 记到这里. 用户点取消/保存/解除后
     * 关闭本界面, 调 {@link #goBack(Minecraft)} 回到这里记的屏幕, 而不是直接关游戏.
     */
    private static Screen parent;

    private EditLinkedScreen() {}

    public static void open(UUID linkId, String currentName, String blockId, int slots,
                           String dimension, int x, int y, int z) {
        open(linkId, currentName, blockId, slots, dimension, x, y, z, Minecraft.getInstance().screen);
    }

    public static void open(UUID linkId, String currentName, String blockId, int slots,
                           String dimension, int x, int y, int z, Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        ModularUI modularUI = buildUI(linkId, currentName, blockId, slots, dimension, x, y, z, mc);
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("container." + ExampleMod.MOD_ID + ".edit_linked.title")));
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

    private static ModularUI buildUI(UUID linkId, String currentName, String blockId, int slots,
                                     String dimension, int x, int y, int z, Minecraft mc) {
        // 根容器: 居中漂浮, 限制宽高
        UIElement root = new UIElement();
        root.setId("edit-linked-root");
        root.layout(l -> l
            .width(360).height(220)
            .paddingAll(14)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        root.style(s -> s.background(Sprites.BORDER));

        // 标题
        Label title = new Label();
        title.setText(Component.literal("编辑已连接容器"));
        title.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER).textShadow(true));
        title.layout(l -> l.height(14).widthPercent(100));
        root.addChild(title);

        // 信息行 1: 类型 + 容量
        Label info1 = new Label();
        info1.setText(Component.literal("§7类型: " + blockId + "    容量: " + slots + " 格"));
        info1.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        info1.layout(l -> l.height(12).widthPercent(100));
        root.addChild(info1);

        // 信息行 2: 位置
        Label info2 = new Label();
        info2.setText(Component.literal("§7位置: " + dimension + " (" + x + ", " + y + ", " + z + ")"));
        info2.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        info2.layout(l -> l.height(12).widthPercent(100));
        root.addChild(info2);

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
        hint.setText(Component.literal("§7留空使用默认名, 点保存后生效"));
        hint.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        hint.layout(l -> l.height(10).widthPercent(100));
        root.addChild(hint);

        // 按钮行: 保存 / 解除连接 / 取消
        UIElement btnRow = new UIElement();
        btnRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .justifyContent(AlignContent.CENTER)
            .gapAll(10)
            .marginTop(6));
        Button saveBtn = new Button();
        saveBtn.setText("保存名字");
        saveBtn.layout(l -> l.width(80).height(20));
        saveBtn.setOnClick(e -> {
            String v = nameField.getValue();
            if (v == null) v = (currentName == null) ? "" : currentName;
            PacketDistributor.sendToServer(new C2SSubmitContainerName(linkId, v));
            goBack(mc);
        });
        btnRow.addChild(saveBtn);

        Button unlinkBtn = new Button();
        unlinkBtn.setText("§c解除连接");
        unlinkBtn.layout(l -> l.width(80).height(20));
        unlinkBtn.setOnClick(e -> {
            // 直接发包, 服务端删数据后会同步给客户端, 客户端的仓库 UI 收到同步包自然刷新
            PacketDistributor.sendToServer(new C2SUnlinkLinkedContainer(linkId));
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

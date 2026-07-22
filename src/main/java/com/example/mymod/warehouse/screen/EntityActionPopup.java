package com.example.mymod.warehouse.screen;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.network.C2SRecallEntity;
import com.example.mymod.warehouse.network.C2SSummonEntity;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
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
 * 实体收容的快捷操作弹窗 —— 玩家在仓库 UI 左键实体图标时弹出.
 * <p>
 * 区别于 {@link EntityEditScreen} (右键图标进入, 用于改名 + 解除 + 召回),
 * 这个弹窗只提供两个核心操作:
 * <ul>
 *   <li>召唤到脚下 (实体在云端时)</li>
 *   <li>召回云端 (实体已在世界里时)</li>
 * </ul>
 * 关闭按钮用于啥都不干直接退出.
 * <p>
 * 布局:
 * <pre>
 * +--------------------------------+
 * |  [icon] 名: 玩家起的名         |
 * |  类型: minecraft:cow           |
 * |                                |
 * |  [ 召唤到脚下 ]   [ 召回云端 ] |
 * |             [ 关闭 ]           |
 * +--------------------------------+
 * </pre>
 */
public final class EntityActionPopup {
    private static Screen parent;

    private EntityActionPopup() {}

    public static void open(UUID linkId, String displayName, ItemStack iconStack, String entityTypeId, boolean summoned) {
        open(linkId, displayName, iconStack, entityTypeId, summoned, Minecraft.getInstance().screen);
    }

    public static void open(UUID linkId, String displayName, ItemStack iconStack, String entityTypeId,
                           boolean summoned, Screen parentScreen) {
        parent = parentScreen;
        Minecraft mc = Minecraft.getInstance();
        ModularUI modularUI = buildUI(linkId, displayName, iconStack, entityTypeId, summoned, mc);
        mc.setScreen(new ModularUIScreen(modularUI,
            Component.translatable("container." + ExampleMod.MOD_ID + ".entity_action.title")));
    }

    private static void goBack(Minecraft mc) {
        if (parent != null) {
            mc.setScreen(parent);
            parent = null;
        } else {
            mc.setScreen(null);
        }
    }

    private static ModularUI buildUI(UUID linkId, String displayName, ItemStack iconStack,
                                     String entityTypeId, boolean summoned, Minecraft mc) {
        UIElement root = new UIElement();
        root.setId("entity-action-root");
        root.layout(l -> l
            .width(280).height(150)
            .paddingAll(14)
            .flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER)
            .gapAll(8));
        root.style(s -> s.background(Sprites.BORDER));

        // 标题行: 图标 + 名字
        UIElement headRow = new UIElement();
        headRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(24)
            .alignItems(AlignItems.CENTER)
            .gapAll(10));

        UIElement iconBox = new UIElement();
        iconBox.layout(l -> l.width(20).height(20));
        iconBox.style(s -> s.background(new ItemStackTexture(iconStack)));
        headRow.addChild(iconBox);

        Label nameLabel = new Label();
        nameLabel.setText(Component.literal("§f名: " + (displayName == null || displayName.isEmpty() ? entityTypeId : displayName)));
        nameLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.LEFT).textShadow(true));
        nameLabel.layout(l -> l.flex(1).height(14));
        headRow.addChild(nameLabel);
        root.addChild(headRow);

        // 类型行
        Label typeLabel = new Label();
        typeLabel.setText(Component.literal("§7类型: " + entityTypeId
            + (summoned ? "    §a状态: 已召唤" : "    §7状态: 存储中")));
        typeLabel.textStyle(s -> s.textAlignHorizontal(Horizontal.CENTER));
        typeLabel.layout(l -> l.height(12).widthPercent(100));
        root.addChild(typeLabel);

        // 按钮行 1: 召唤 / 召回
        UIElement actRow = new UIElement();
        actRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(24)
            .justifyContent(AlignContent.CENTER)
            .gapAll(12)
            .marginTop(6));

        Button summonBtn = new Button();
        summonBtn.setText(summoned ? "§7召唤 (已召唤)" : "§a召唤到脚下");
        summonBtn.layout(l -> l.width(100).height(22));
        summonBtn.setOnClick(e -> {
            if (summoned) return; // 已召唤, 不再 spawn
            PacketDistributor.sendToServer(new C2SSummonEntity(linkId));
            goBack(mc);
        });
        actRow.addChild(summonBtn);

        Button recallBtn = new Button();
        recallBtn.setText(summoned ? "§e召回云端" : "§7召回 (需先召唤)");
        recallBtn.layout(l -> l.width(100).height(22));
        recallBtn.setOnClick(e -> {
            if (!summoned) return;
            PacketDistributor.sendToServer(new C2SRecallEntity(linkId));
            goBack(mc);
        });
        actRow.addChild(recallBtn);

        root.addChild(actRow);

        // 按钮行 2: 关闭
        UIElement closeRow = new UIElement();
        closeRow.layout(l -> l
            .flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(22)
            .justifyContent(AlignContent.CENTER)
            .marginTop(4));
        Button closeBtn = new Button();
        closeBtn.setText("关闭");
        closeBtn.layout(l -> l.width(80).height(20));
        closeBtn.setOnClick(e -> goBack(mc));
        closeRow.addChild(closeBtn);
        root.addChild(closeRow);

        return ModularUI.of(UI.of(root), mc.player);
    }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 仓库模组所有自定义网络包的注册中心. */
public final class WarehouseNetworking {
    private WarehouseNetworking() {}

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
            C2SOpenWarehouse.TYPE,
            C2SOpenWarehouse.STREAM_CODEC,
            C2SOpenWarehouse.HANDLER
        );
        registrar.playToClient(
            S2CSyncLinkedContainers.TYPE,
            S2CSyncLinkedContainers.STREAM_CODEC,
            S2CSyncLinkedContainers.HANDLER
        );
        registrar.playToClient(
            S2COpenNameEntry.TYPE,
            S2COpenNameEntry.STREAM_CODEC,
            S2COpenNameEntry.HANDLER
        );
        registrar.playToServer(
            C2SSubmitContainerName.TYPE,
            C2SSubmitContainerName.STREAM_CODEC,
            C2SSubmitContainerName.HANDLER
        );
        registrar.playToServer(
            C2SOpenLinkedContainer.TYPE,
            C2SOpenLinkedContainer.STREAM_CODEC,
            C2SOpenLinkedContainer.HANDLER
        );
        registrar.playToServer(
            C2SSwitchLinkedContainerPage.TYPE,
            C2SSwitchLinkedContainerPage.STREAM_CODEC,
            C2SSwitchLinkedContainerPage.HANDLER
        );
        registrar.playToClient(
            S2CSyncLinkedContainerPage.TYPE,
            S2CSyncLinkedContainerPage.STREAM_CODEC,
            S2CSyncLinkedContainerPage.HANDLER
        );
        registrar.playToServer(
            C2SOpenUpgradeScreen.TYPE,
            C2SOpenUpgradeScreen.STREAM_CODEC,
            C2SOpenUpgradeScreen.HANDLER
        );
        registrar.playToClient(
            S2CSyncUpgradeState.TYPE,
            S2CSyncUpgradeState.STREAM_CODEC,
            S2CSyncUpgradeState.HANDLER
        );
        registrar.playToServer(
            C2SSubmitUpgradeMaterials.TYPE,
            C2SSubmitUpgradeMaterials.STREAM_CODEC,
            C2SSubmitUpgradeMaterials.HANDLER
        );
        // 编辑已连接容器: 玩家右键图标 → C2SRequestEditLinked → 服务端回 S2COpenEditLinked 开屏
        registrar.playToServer(
            C2SRequestEditLinked.TYPE,
            C2SRequestEditLinked.STREAM_CODEC,
            C2SRequestEditLinked.HANDLER
        );
        registrar.playToClient(
            S2COpenEditLinked.TYPE,
            S2COpenEditLinked.STREAM_CODEC,
            S2COpenEditLinked.HANDLER
        );
        // 解除连接: 编辑界面点 "解除连接" → 服务端把 link 从数据里删掉
        registrar.playToServer(
            C2SUnlinkLinkedContainer.TYPE,
            C2SUnlinkLinkedContainer.STREAM_CODEC,
            C2SUnlinkLinkedContainer.HANDLER
        );
        // 仓库本体分页切换
        registrar.playToServer(
            C2SSwitchWarehousePage.TYPE,
            C2SSwitchWarehousePage.STREAM_CODEC,
            C2SSwitchWarehousePage.HANDLER
        );
        registrar.playToClient(
            S2CSyncWarehousePage.TYPE,
            S2CSyncWarehousePage.STREAM_CODEC,
            S2CSyncWarehousePage.HANDLER
        );
    }

    /** 工具: 服务端发包给指定玩家 (S2C). */
    public static void sendTo(net.minecraft.server.level.ServerPlayer player, CustomPacketPayload payload) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
    }
}

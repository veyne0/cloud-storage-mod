package com.example.mymod.warehouse.client;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.menu.LinkedContainerMenu;
import com.example.mymod.warehouse.menu.MenuTypes;
import com.example.mymod.warehouse.menu.WarehouseMenu;
import com.example.mymod.warehouse.screen.LinkedContainerScreen;
import com.example.mymod.warehouse.screen.WarehouseScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端启动: 注册 Screen 与按键 tick. 全部走 @SubscribeEvent, 不需要 mod 构造里手动 addListener. */
@EventBusSubscriber(modid = ExampleMod.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(MenuTypes.WAREHOUSE.get(), WarehouseScreen::new);
        event.register(MenuTypes.LINKED.get(), LinkedContainerScreen::new);
    }

    private static int tickCount = 0;
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (++tickCount % 200 == 0) {
            ExampleMod.LOGGER.info("[Warehouse] ClientSetup.onClientTick fired 200 times (every 10s), checking V key");
        }
        WarehouseKeybinds.clientTick();
    }
}

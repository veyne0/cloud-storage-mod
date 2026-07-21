package com.example.mymod.warehouse.client;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.network.C2SOpenWarehouse;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端快捷键: 默认 V 打开云存储.
 * <p>
 * 注意区分 InputConstants.Type.KEYSYM (键鼠) 和 MOUSE (鼠标按键). 我们这里只绑键鼠.
 */
@EventBusSubscriber(modid = ExampleMod.MOD_ID, value = Dist.CLIENT)
public class WarehouseKeybinds {
    public static final String CATEGORY = "key.categories." + ExampleMod.MOD_ID;

    public static final KeyMapping OPEN_WAREHOUSE = new KeyMapping(
        "key." + ExampleMod.MOD_ID + ".open_warehouse",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_V,
        CATEGORY
    );

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        ExampleMod.LOGGER.info("[Warehouse] Registering V key: name='{}' category='{}' keyCode=V({})",
            OPEN_WAREHOUSE.getName(), CATEGORY, GLFW.GLFW_KEY_V);
        event.register(OPEN_WAREHOUSE);
    }

    /** 在客户端 tick 里检查按键, 按了就发包给服务端. */
    public static void clientTick() {
        // consumeClick 内部会检测 GLFW 状态. 任何时候只返回一次 true 直到按钮释放.
        while (OPEN_WAREHOUSE.consumeClick()) {
            ExampleMod.LOGGER.info("[Warehouse] V key consumed! sending C2SOpenWarehouse to server");
            PacketDistributor.sendToServer(new C2SOpenWarehouse());
        }
    }
}

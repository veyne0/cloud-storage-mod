package com.example.mymod;

import com.example.mymod.warehouse.WarehouseDataManager;
import com.example.mymod.warehouse.WarehouseEvents;
import com.example.mymod.warehouse.item.ContainerConnectorItem;
import com.example.mymod.warehouse.menu.MenuTypes;
import com.example.mymod.warehouse.network.WarehouseNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

@Mod(ExampleMod.MOD_ID)
public class ExampleMod {
    public static final String MOD_ID = "examplemod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ---- 仓库模组注册表 ----
    public static final DeferredRegister.Items WAREHOUSE_ITEMS =
        DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> WAREHOUSE_TABS =
        DeferredRegister.create(BuiltInRegistries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredItem<Item> CONTAINER_CONNECTOR = WAREHOUSE_ITEMS.register(
        "container_connector",
        () -> new ContainerConnectorItem(new Item.Properties().stacksTo(1))
    );

    public static final java.util.function.Supplier<CreativeModeTab> WAREHOUSE_TAB =
        WAREHOUSE_TABS.register("warehouse_tab", () -> CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup." + ExampleMod.MOD_ID + ".warehouse"))
            .icon(() -> new ItemStack(CONTAINER_CONNECTOR.get()))
            // displayItems 自己 tab 加一份 (走原版的 CreativeModeTab.Output.accept 路径)
            .displayItems((params, output) -> output.accept(CONTAINER_CONNECTOR.get()))
            .build());

    /**
     * 防止 {@link BuildCreativeModeTabContentsEvent} 在同一 tab 触发两次时重复 add 同一 ItemStack.
     * <p>
     * 1.21.1 的 {@code CreativeModeTabs.buildAllTabContents} 会逐个调 {@code buildContents},
     * 里面又会 post {@code BuildCreativeModeTabContentsEvent} 一次 — 也就是说, mod 自己
     * (在 displayItems 里) 加过的东西, 事件再触发时如果再 add 一次, NeoForge 1.21.1 严格
     * 检查会抛 {@code IllegalArgumentException: already exists in the tab's list}.
     * <p>
     * 我们自己 tab 已经走 displayItems 加了, 这里只挂到原版 TOOLS tab, 用 processedTabs
     * 防止二次触发时重复 add.
     */
    private final Set<ResourceKey<CreativeModeTab>> processedTabs = new HashSet<>();

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("ExampleMod constructing...");

        // 物品 / 创造栏 / 菜单类型
        WAREHOUSE_ITEMS.register(modEventBus);
        WAREHOUSE_TABS.register(modEventBus);
        MenuTypes.REGISTER.register(modEventBus);

        // 服务端: 注册 SavedData 加载钩子
        WarehouseDataManager.register();
        WarehouseEvents.register();

        // 网络包
        modEventBus.addListener(this::registerPackets);

        // 创造栏内容
        modEventBus.addListener(this::addCreativeTabContents);

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void registerPackets(RegisterPayloadHandlersEvent event) {
        WarehouseNetworking.register(event.registrar("1.0"));
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // 只挂到原版 TOOLS tab; WAREHOUSE_TAB 已经在 displayItems lambda 里 add 过了.
        // processedTabs 防止事件多次触发时重复 add.
        ResourceKey<CreativeModeTab> key = event.getTabKey();
        if (key.equals(CreativeModeTabs.TOOLS_AND_UTILITIES) && processedTabs.add(key)) {
            event.accept(CONTAINER_CONNECTOR.get());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("ExampleMod common setup done.");
    }

    private void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("ExampleMod server starting.");
    }
}

package com.example.mymod.warehouse.menu;

import com.example.mymod.ExampleMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 所有自定义菜单类型的注册表. */
public class MenuTypes {
    public static final DeferredRegister<MenuType<?>> REGISTER =
        DeferredRegister.create(BuiltInRegistries.MENU, ExampleMod.MOD_ID);

    /**
     * 走 IContainerFactory 的 3 参数 {@code create(int, Inventory, RegistryFriendlyByteBuf)} 完整版.
     * <p>
     * 显式写出类型避免两个构造器之间的歧义; 内部用 {@code MenuTypes.WAREHOUSE.get()} 拿到 MenuType
     * (Supplier 是 deferred, lambda 第一次执行时 WAREHOUSE 已被注册, 不会 NPE).
     */
    public static final Supplier<MenuType<WarehouseMenu>> WAREHOUSE =
        REGISTER.register("warehouse", MenuTypes::build);

    private static MenuType<WarehouseMenu> build() {
        return IMenuTypeExtension.create(MenuTypes::createMenu);
    }

    private static WarehouseMenu createMenu(int containerId, Inventory inv, RegistryFriendlyByteBuf buf) {
        return new WarehouseMenu(WAREHOUSE.get(), containerId, inv);
    }

    /**
     * 已连接容器: 远距离打开, 无 stillValid 距离检查.
     * <p>
     * 3 参数 lambda 走 {@link net.neoforged.neoforge.network.IContainerFactory} 的完整
     * {@code create(int, Inventory, RegistryFriendlyByteBuf)}. 服务端把容器槽数写进
     * buf, 客户端读出, 双方 menu 的 slot 数一致 —— 这样 IronChest 等任意大小
     * 的容器都能开, 不会因为 client/server 槽数不一致触发
     * {@code IndexOutOfBoundsException}, 也不会把玩家物品错位同步.
     */
    public static final Supplier<MenuType<LinkedContainerMenu>> LINKED =
        REGISTER.register("linked_container", MenuTypes::buildLinked);

    private static MenuType<LinkedContainerMenu> buildLinked() {
        return IMenuTypeExtension.create(MenuTypes::createLinked);
    }

    private static LinkedContainerMenu createLinked(int containerId, Inventory inv,
                                                    RegistryFriendlyByteBuf buf) {
        // 客户端: server 在 OpenMenu 包里把 containerSlots 写进了 buf
        int containerSlots = buf.readInt();
        // 客户端不需要真 handler, 传 null 让 LinkedContainerMenu 用空 ItemStackHandler 占位
        // (server 之后会通过 ContainerSetContent 包把真实内容覆盖)
        return new LinkedContainerMenu(LINKED.get(), containerId, inv, containerSlots, null);
    }
}

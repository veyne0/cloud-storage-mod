package com.example.mymod.warehouse.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 全局覆盖 {@link AbstractContainerMenu#stillValid(ContainerLevelAccess, Player, Block)}:
 * 永远返回 true, 让所有"远距离打开的菜单"不会因为玩家走远而自动关闭.
 * <p>
 * 为什么这样做:
 * <ol>
 *   <li>1.21.1 的 {@code AbstractContainerMenu} 已经没有 public 抽象 {@code stillValid}
 *       实例方法 (字段从基类下沉到各具体菜单), 但每个具体菜单的 {@code stillValid} 最终
 *       都会调用这个 protected static 方法做 8 格距离检查.</li>
 *   <li>反射改 {@code access} 字段只能盖住"用 ContainerLevelAccess.evaluate 的菜单",
 *       Mekanism 等 mod 不用这个, 还是会关.</li>
 *   <li>拉大 {@code BLOCK_INTERACTION_RANGE} 属性只到 64 (属性上限), 超过 65 格的
 *       容器还是会被关.</li>
 *   <li>用 mixin 把这个 static 方法整个改成 {@code return true;}, 一劳永逸,
 *       不管什么 mod 都不会再自动关.</li>
 * </ol>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    /**
     * 覆盖 vanilla 8 格距离检查, 永远返回 true.
     */
    @Overwrite
    protected static boolean stillValid(ContainerLevelAccess access, Player player, Block block) {
        return true;
    }
}

package com.example.mymod.warehouse.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * 覆盖 {@link BaseContainerBlockEntity#stillValid(Player)} 永远返回 true.
 * <p>
 * <b>为什么这样改能解决"原版容器打开后隔远自动关闭"</b>:
 * <ol>
 *   <li>1.21.1 的 {@code AbstractContainerMenu} 没有 {@code access} 字段, 之前反射
 *       改 access 字段的策略实际上找不到任何东西, 完全没生效.</li>
 *   <li>真正的距离检查在 {@code BaseContainerBlockEntity.stillValid(Player)} 里,
 *       它调用 {@code Container.stillValidBlockEntity(this, player)}, 后者用
 *       {@code player.distanceToSqr(be.blockPos) <= 64.0} 做 8 格距离判断.</li>
 *   <li>几乎所有"方块本身是容器"的 BlockEntity (原版箱子/熔炉/桶/工作台/漏斗/投掷器/
 *       酿造台/信标/附魔台/潜影盒/箱子, 以及 Iron Chests 等 mod 只要继承
 *       {@code BaseContainerBlockEntity} 或调用 {@code Container.stillValidBlockEntity}
 *       的) 都会受影响.</li>
 *   <li>覆盖这个方法后, {@code ServerPlayer.tick()} 里
 *       {@code containerMenu.stillValid(this)} → ChestMenu.stillValid → container.stillValid
 *       → 我们的 Mixin → true, 远距离也不会关.</li>
 * </ol>
 *
 * <p>与 {@link AbstractContainerMenuMixin} 配合: 那个改静态 helper, 这个改 BE 实现,
 * 双保险, 几乎所有方块类型都被覆盖.
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {
    /**
     * 覆盖原版 BaseContainerBlockEntity.stillValid, 永远返回 true,
     * 让远距离打开的容器不自动关闭.
     */
    @Overwrite
    public boolean stillValid(Player player) {
        return true;
    }
}

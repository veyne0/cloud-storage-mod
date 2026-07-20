package com.example.mymod.warehouse.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 终极保险: 直接拦截 {@link ServerPlayer#tick} 里 "containerMenu.stillValid(player)"
 * 的判定, 强制返回 true. 这样不管菜单是原版还是 Iron Chests / Mekanism / 其他 mod,
 * 服务器都不会因为距离过远而自动关闭.
 * <p>
 * <b>为什么需要这个 Mixin</b>:
 * <ol>
 *   <li>{@code ServerPlayer.tick()} 末尾会做:
 *       <pre>
 *       if (containerMenu != inventoryMenu) {
 *           if (!containerMenu.stillValid(this)) closeContainer();
 *       }
 *       </pre>
 *       每个具体菜单 (ChestMenu, IronChestMenu, ...) 的 {@code stillValid} 最终都会
 *       调用方块实体的距离检查. 不同 mod 的实现路径不同, 一个 BaseContainerBlockEntity
 *       Mixin 不一定盖得住所有.</li>
 *   <li>{@code @Redirect} 直接在 ServerPlayer.tick 的字节码层把这个
 *       {@code stillValid} 调用替换成 {@code return true}, 不再走原来那条链.
 *       这是最狠的兜底, 不管什么 mod, 远距离不会关.</li>
 * </ol>
 *
 * <p>副作用: 玩家跨维度/退出游戏时, 容器还是会正常关 (那条 closeContainer 路径在
 * 其他地方也会触发, 这里只拦了 tick 的这一处距离检查).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMenuMixin {
    /**
     * 把 {@code ServerPlayer.tick} 里
     * {@code if (!this.containerMenu.stillValid(this)) this.closeContainer();}
     * 中的 {@code stillValid} 调用重定向, 永远返回 true.
     * <p>
     * 注意: 不直接 redirect closeContainer, 因为我们想保留其他触发关闭的逻辑
     * (例如玩家退出/死亡/被踢). 只让"距离不达标而关"这条失效.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"
        )
    )
    private boolean warehouse$alwaysValid(AbstractContainerMenu menu, Player player) {
        return true;
    }
}

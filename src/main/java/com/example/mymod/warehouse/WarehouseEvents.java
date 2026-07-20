package com.example.mymod.warehouse;

import com.example.mymod.warehouse.network.C2SOpenLinkedContainer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 仓库模组事件钩子 —— 集中处理"打开/关闭容器时清理副作用".
 * <p>
 * 主要是 C2SOpenLinkedContainer 给玩家加的 {@code BLOCK_INTERACTION_RANGE} 临时
 * modifier: 打开远距离容器时拉大到 128 格, 关闭 (或玩家下线) 时立即移除, 防止无限延伸.
 */
public final class WarehouseEvents {
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(WarehouseEvents.class);
    }

    private WarehouseEvents() {}

    /**
     * 任何容器关闭都移除 modifier (保险起见), 包含:
     * <ul>
     *   <li>玩家主动 ESC 关闭</li>
     *   <li>玩家点另一个菜单, 服务器把旧 menu 当 "closed"</li>
     *   <li>玩家死亡/重生时 vanilla 关菜单</li>
     * </ul>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            C2SOpenLinkedContainer.removeFarInteractionModifier(sp);
        }
    }

    /** 玩家下线: 清理 modifier, 避免重连后还带着. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            C2SOpenLinkedContainer.removeFarInteractionModifier(sp);
        }
    }
}

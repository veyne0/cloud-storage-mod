package com.example.mymod.warehouse.mixin;

import com.example.mymod.warehouse.screen.WarehouseScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让玩家在仓库界面点击"已连接容器"图标 -> 实际容器打开后, 关闭时自动返回仓库界面,
 * 而不是直接退到游戏世界.
 *
 * 实现原理:
 * 1. {@link WarehouseScreen} 留一个静态字段 pendingReturn, 在玩家点击容器图标时把 this 写进去.
 *    切到下一个屏 (容器屏) 时 pendingReturn 保持不变.
 * 2. 本 Mixin 拦截 Minecraft.setScreen:
 *    - 新屏非 null 且是 AbstractContainerScreen (开容器): 保留 pendingReturn, 等待下次 setScreen(null) 触发
 *    - 新屏非 null 且不是 AbstractContainerScreen 也不是 WarehouseScreen: 清掉 pending 避免误触发
 *    - 新屏为 null 且 pending 还有效: 立刻恢复 pending
 * 3. 用 mc.execute(...) 延迟一帧, 避免和当前 setScreen 调用形成递归栈.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void warehouse$interceptClose(Screen newScreen, CallbackInfo ci) {
        if (newScreen != null) {
            // 玩家正在切到一个新屏. 如果是 AbstractContainerScreen (开容器), 保留 pendingReturn
            // 等它关闭时消费; 否则清掉, 避免误触发.
            if (!(newScreen instanceof AbstractContainerScreen)
                && !(newScreen instanceof WarehouseScreen)) {
                WarehouseScreen.clearPendingReturn();
            }
            return;
        }
        // newScreen == null: 玩家正在关闭当前屏
        Screen pending = WarehouseScreen.consumePendingReturn();
        if (pending == null) return;
        // 当前正要关闭的屏是 AbstractContainerScreen (我们打开的容器) 吗?
        Minecraft mc = (Minecraft) (Object) this;
        if (!(mc.screen instanceof AbstractContainerScreen)) {
            // 不是容器 (比如玩家手动关掉仓库屏本身), 不做处理
            return;
        }
        // 延迟到下一帧恢复, 避免和当前 setScreen(null) 形成递归
        Screen toRestore = pending;
        mc.execute(() -> {
            if (mc.screen == null) {
                mc.setScreen(toRestore);
            }
        });
    }
}

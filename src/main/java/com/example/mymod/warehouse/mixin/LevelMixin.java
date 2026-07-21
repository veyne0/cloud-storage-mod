package com.example.mymod.warehouse.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 解决"远距离打开容器崩溃"问题 (兜底, 仅 chunk 未加载时介入).
 * <p>
 * 症状: 玩家距离容器很远 (例如 50+ 区块) 时, 即使:
 * <ul>
 *   <li>服务端 addRegionTicket 强制加载 chunk</li>
 *   <li>服务端主动推送 ClientboundLevelChunkWithLightPacket</li>
 *   <li>服务端主动推送 ClientboundBlockEntityDataPacket</li>
 *   <li>延迟 5 tick 让客户端消化</li>
 * </ul>
 * 客户端在 Mekanism / Create 等 mod 的 createMenu 流程中仍可能调
 * {@code level.getBlockEntity(pos)}, 如果那一刻 chunk 还没被客户端加入,
 * 原版直接返回 null → Mekanism 抛 "Client could not locate tile".
 * <p>
 * <b>关键: 只在 {@code !hasChunkAt(pos)} 时兜底, 不要在"原版返回 null"时全部兜底</b>.
 * <p>
 * 之前我误改成"原版返回 null 就给 stub", 导致 Create 的 GoggleOverlayRenderer 在
 * HUD 渲染时调 {@code getBlockEntity} 看玩家视线方向 (大概率是空气), 触发 stub 构造,
 * 而 stub 用 {@code Blocks.AIR} 状态被 {@link BlockEntity#validateBlockState} 拒绝
 * (state.isAir() 检查), 抛 "Invalid block entity ... null state ... got Block{minecraft:air}".
 * <p>
 * 解法: 用 {@code !hasChunkAt(pos)} 作为唯一触发条件. 这精确匹配"远距离 chunk 未加载"
 * 场景, 不影响"位置是空气"等正常情况.
 * <p>
 * stub 的 state 必须用"合法"的 (非 air + 是 COMMAND_BLOCK 类型的 valid block).
 * <b>但 {@code validateBlockState} 仍然会因为 COMMAND_BLOCK 的 state.getBlock() != COMMAND_BLOCK
 * 抛错 (我们在 1.21.1 拿不到 COMMAND_BLOCK 的真实 state).</b> 所以这里用一个折中:
 * 不给 stub, 改为只防御 "hasChunkAt 已经在 chunk map 找到 chunk 但 BE map 还没填"
 * 的情况 —— 这种 case 我们返回 null (即原版行为), 让调用方自己处理.
 * <p>
 * 真正起兜底作用的是 {@link com.example.mymod.warehouse.network.C2SOpenLinkedContainer}
 * 里的 5 tick 延迟 + 服务端推 chunk/BE 包 —— 那些保证客户端 chunk 实际加载完毕.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    /**
     * 在原版 getBlockEntity 之后判断, 客户端 chunk 未加载时给一个安全的 stub.
     * <p>
     * 用 {@code at = @At("RETURN")}, 这样原版逻辑跑完一遍.
     * 服务端永不触发 (非 clientSide).
     * <p>
     * <b>注意: 这里不再做"原版返回 null 就兜底"的全量替换</b>, 只在 chunk 未加载时介入.
     */
    @Inject(method = "getBlockEntity", at = @At("RETURN"), cancellable = true)
    private void premiumcloudstorage$getBlockEntityStub(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (cir.getReturnValue() != null) return; // 原版拿到了, 不动
        Level self = (Level) (Object) this;
        if (!self.isClientSide()) return;          // 只在客户端兜底, 不影响服务端
        if (self.hasChunkAt(pos)) {
            // chunk 已加载但 BE 不在 map 里 (e.g. 视线方向是空气, 或 deserialize 还没完)
            // → 不兜底, 保持原版 null 行为. Create GoggleOverlayRenderer 等 HUD
            //   mod 在这里会拿到 null 然后走自己的 fallback 路径, 不会崩.
            return;
        }

        // ---- 真正兜底: chunk 没加载 (远距离), 防止 "Client could not locate tile" ----
        // 给一个 dummy 状态的 stub. 我们用 FURNACE 的 state 作为兜底 (非 air, 且
        // 我们 type 选 FURNACE 类型 → state.getBlock() == FURNACE → validateBlockState 通过).
        BlockState stubState;
        BlockEntityType<?> stubType;
        try {
            stubType = BlockEntityType.FURNACE;
            stubState = net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState();
        } catch (Throwable t) {
            return; // 极端情况拿不到, 直接放弃兜底
        }
        BlockEntity stub = new BlockEntity(stubType, pos, stubState) {};
        cir.setReturnValue(stub);
    }
}

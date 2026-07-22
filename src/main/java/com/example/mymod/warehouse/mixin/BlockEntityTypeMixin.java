package com.example.mymod.warehouse.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 客户端兜底 Mixin: 防止 "Incorrect block entity at X expected to find Y" 异常
 * 把玩家踢回服务器列表.
 *
 * <h2>背景</h2>
 * Minecraft 1.21.1 改了 {@link BlockEntityType#create(BlockPos, BlockState)} 的行为:
 * <ul>
 *   <li>旧版 (1.20.x): block 不在 validBlocks 集合时, <b>return null</b>, 调用方走 "BE 不存在" 路径</li>
 *   <li>1.21.1: block 不在 validBlocks 集合时, <b>抛 IllegalArgumentException</b></li>
 * </ul>
 *
 * <p>抛出的异常格式: {@code "Incorrect block entity at BlockPos{x=-59, y=67, z=75}
 * expected to find StorageBlockEntity"}. 这个异常会沿网络包处理链冒泡到
 * {@code Connection.handler}, Minecraft 把它当成"网络包处理错误", 关闭连接
 * 并显示 "打开带有高级数据的屏幕失败: ..." 然后把玩家踢回服务器列表.
 *
 * <h2>触发场景</h2>
 * <ol>
 *   <li>玩家通过云存储点击了一个 mod 容器 (e.g. Sophisticated Storage 的某个方块)</li>
 *   <li>服务端主动 push {@code ClientboundBlockEntityDataPacket} 给客户端,
 *       带上真实的 BlockEntity NBT</li>
 *   <li>客户端收到包, 在 chunk 还没完全 sync 完 / chunk 已被换成不同方块 /
 *       玩家远离导致 chunk 状态不一致时, 调用 {@code type.create(pos, state)} 重建 BE
 *       但抛异常</li>
 * </ol>
 *
 * <h2>解决方案 (两层防御)</h2>
 * <ol>
 *   <li><b>服务端 (主防线)</b>: {@code C2SOpenLinkedContainer} 在 push BE 前用反射检查
 *       {@code type.validBlocks} 是否包含当前位置的 block. 不包含则:
 *       <ul>
 *         <li>把 link 自动标记为"高级容器" (175 格距离限制)</li>
 *         <li>跳过 push BE, 让 {@code LevelMixin} 提供的 stub BE 兜底</li>
 *       </ul>
 *   </li>
 *   <li><b>本 Mixin (客户端兜底)</b>: 即便服务端的检测没拦下 (例如别的 mod 走自己的 push 路径),
 *       这里在 {@code create} 入口检查 validBlocks, 不包含就直接返回 null, 恢复旧版本
 *       行为, 永远不让玩家被踢.</li>
 * </ol>
 */
@Mixin(BlockEntityType.class)
public abstract class BlockEntityTypeMixin {
    /**
     * 反射 {@code BlockEntityType} 里的 validBlocks 集合, 用于在 {@link #premiumcloudstorage$safeCreate}
     * 入口判断当前位置的 block 跟当前 BE 类型是否兼容.
     */
    @Shadow
    private Set<Block> validBlocks;

    /**
     * 拦截 {@link BlockEntityType#create(BlockPos, BlockState)} 入口.
     * <p>
     * 原版 1.21.1 在 block 不在 validBlocks 集合里时会抛 {@code IllegalArgumentException}.
     * 我们提前判断, 不兼容就直接返回 null, 避免异常沿网络包处理链冒泡导致玩家被踢.
     *
     * @param cir 用来 setReturnValue + cancel 原方法
     */
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private void premiumcloudstorage$safeCreate(BlockPos pos, BlockState state, CallbackInfoReturnable<BlockEntity> cir) {
        if (validBlocks != null && state != null && !validBlocks.contains(state.getBlock())) {
            // block 跟 BE 类型不兼容. 1.21.1 会抛 IllegalArgumentException 把玩家踢回服务器列表.
            // 我们恢复 1.20.x 的旧行为: return null, 让调用方走 "BE 不存在" 路径.
            cir.setReturnValue(null);
        }
    }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 客户端 → 服务端: 玩家在仓库界面点了一个"已连接容器"图标, 请服务端打开它.
 *
 * <h2>核心策略</h2>
 * 始终打开容器方块自带的原版/原模组界面, 永不自定义菜单.
 * <ol>
 *   <li>{@code BlockState.getMenuProvider(level, pos)} - 标准 API</li>
 *   <li>反射 fallback: 找 BlockEntity.getMenuProvider() (部分 mod 用 legacy API)</li>
 *   <li>再不行就模拟右键 useWithoutItem (某些 mod 不实现 MenuProvider, 用 use 来开)</li>
 * </ol>
 *
 * <h2>同维度无距离限制</h2>
 * 跨维度会直接拒绝 (因为 ChunkPos 不同). 同维度内:
 * <ol>
 *   <li>{@link #forceLoadChunk} 强加载 + addRegionTicket 保活</li>
 *   <li>{@link #sendChunkToClient} 主动推 chunk 包 (不靠服务端 tick 自然推)</li>
 *   <li>{@link #sendBlockEntityToClient} 主动推 BlockEntity 包 (绕开反序列化时序问题)</li>
 *   <li>延迟 5 tick 后用 {@link NetworkHooks#openScreen} 打开 (走 NeoForge payload,
 *       跨模组兼容性更好)</li>
 *   <li>客户端 {@link com.example.mymod.warehouse.mixin.LevelMixin} 兜底: 如果
 *       chunk 还没就绪, 返回 stub BE, 防止 "Client could not locate tile" 崩溃</li>
 *   <li>服务端 {@link com.example.mymod.warehouse.mixin.BaseContainerBlockEntityMixin}
 *       + {@link com.example.mymod.warehouse.mixin.ServerPlayerMenuMixin} 兜底:
 *       距离再远 stillValid 永远返回 true, 容器不会自动关</li>
 * </ol>
 */
public record C2SOpenLinkedContainer(UUID linkId) implements CustomPacketPayload {
    public static final Type<C2SOpenLinkedContainer> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "open_linked"));
    public static final StreamCodec<ByteBuf, C2SOpenLinkedContainer> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC,
            C2SOpenLinkedContainer::linkId,
            C2SOpenLinkedContainer::new
        );

    /**
     * 延迟多少 tick 再开屏, 让客户端有时间消化 chunk + BE 包.
     * <p>
     * 5 tick = 0.25 秒, 配合 pushChunk + pushBE + LevelMixin 兜底, 兼容 99% mod.
     * 极少数 BE 反序列化极慢的 mod (如 Mekanism 风力发电机) 仍可能慢, 5 tick 兜不住时
     * 客户端会拿到 LevelMixin 提供的 stub BE, 菜单能开但 mod 特殊 UI (能量条等) 不渲染.
     */
    private static final int OPEN_DELAY_TICKS = 5;

    public static final IPayloadHandler<C2SOpenLinkedContainer> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var data = WarehouseDataManager.get(sp);
            var link = data.findLink(payload.linkId);
            if (link == null) return;

            // 跨维度: 直接拒绝 (用户明确不要这个功能).
            if (sp.level().dimension() != link.dimension()) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u5bb9\u5668\u5728\u53e6\u4e00\u4e2a\u7ef4\u5ea6, \u8bf7\u5148\u53bb "
                        + link.dimension().location() + " \u518d\u6253\u5f00"));
                return;
            }

            ServerLevel level = sp.serverLevel();
            BlockPos pos = link.pos();
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u90a3\u4e2a\u4f4d\u7f6e\u5df2\u7ecf\u6ca1\u6709\u65b9\u5757\u4e86, \u5df2\u65ad\u5f00\u8fde\u63a5"));
                data.removeLink(payload.linkId);
                WarehouseDataManager.setDirty();
                WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                return;
            }

            // ---- 拿到 MenuProvider (3 种 fallback) ----
            MenuProvider provider = be.getBlockState().getMenuProvider(level, pos);
            if (provider == null) {
                provider = findMenuProviderViaReflection(be);
                if (provider != null) {
                    ExampleMod.LOGGER.info("[Warehouse] Found MenuProvider via reflection on {}",
                        be.getClass().getSimpleName());
                }
            }
            if (provider == null) {
                // 模拟右键: 某些 mod (例如某些 custom 机器) 只在 useWithoutItem 里打开 GUI
                ExampleMod.LOGGER.info("[Warehouse] No MenuProvider for {} at {}; simulating right-click",
                    be.getBlockState().getBlock().getName().getString(), pos);
                BlockHitResult hitResult = new BlockHitResult(
                    Vec3.atCenterOf(pos), Direction.UP, pos, false);
                InteractionResult r = level.getBlockState(pos).useWithoutItem(level, sp, hitResult);
                if (!r.consumesAction()) {
                    String name = be.getBlockState().getBlock().getName().getString();
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7e[\u4e91\u5b58\u50a8] \"" + name + "\" \u627e\u4e0d\u5230\u5bb9\u5668\u63a5\u53e3, \u8bf7\u9760\u8fd1\u540e\u53f3\u952e."));
                }
                return;
            }

            // ---- 准备: 强加载 + 推 chunk + 推 BE ----
            forceLoadChunk(level, pos);
            sendChunkToClient(sp, level, pos);
            sendBlockEntityToClient(sp, level, pos);

            // ---- 包装一层: 用玩家填的名字 ----
            String customName = (link.name() == null || link.name().isEmpty()) ? null : link.name();
            MenuProvider wrapped = wrapForCustomName(provider, customName);

            // ---- 延迟 N tick 后开屏 ----
            // 客户端接收顺序: chunk 包 → BE 包 → (5 tick 后) openScreen 包
            // 客户端 MultiPlayerGameMode.handleOpenScreen 在收到 openScreen 时
            // 会调 level.getBlockEntity(pos), 此时 chunk + BE 都已经就绪.
            // 万一时序不稳, LevelMixin 提供 stub BE 兜底, 永不崩.
            scheduleOpenScreen(sp, wrapped, pos, OPEN_DELAY_TICKS);
        });
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ==================== 辅助方法 ====================

    /** 反射: 找 BlockEntity 上的 {@code getMenuProvider()} (无参) 方法. */
    private static MenuProvider findMenuProviderViaReflection(BlockEntity be) {
        try {
            Class<?> current = be.getClass();
            while (current != null && current != Object.class) {
                for (Method m : current.getDeclaredMethods()) {
                    if (!m.getName().equals("getMenuProvider")) continue;
                    if (m.getParameterCount() != 0) continue;
                    if (!MenuProvider.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    Object result = m.invoke(be);
                    if (result instanceof MenuProvider mp) {
                        return mp;
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug("[Warehouse] Reflection fallback failed for {}",
                be.getClass().getName(), t);
        }
        return null;
    }

    /** 强加载 chunk + 保活 (server tick 内有效, 关屏后失效). */
    private static void forceLoadChunk(ServerLevel level, BlockPos pos) {
        try {
            ChunkPos chunkPos = new ChunkPos(pos);
            level.getChunk(chunkPos.x, chunkPos.z);
            level.getChunkSource().addRegionTicket(
                TicketType.FORCED,
                chunkPos,
                0,
                chunkPos
            );
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to force-load chunk at {}", pos, t);
        }
    }

    /**
     * 主动把目标 chunk 推给该玩家客户端.
     * <p>
     * 客户端不靠服务端 tick 自然推送, 我们直接构造
     * {@link ClientboundLevelChunkWithLightPacket} 发过去, 下一次网络 flush 客户端就能拿到.
     */
    private static void sendChunkToClient(ServerPlayer sp, ServerLevel level, BlockPos pos) {
        try {
            ChunkPos chunkPos = new ChunkPos(pos);
            net.minecraft.world.level.chunk.ChunkAccess chunkAccess =
                level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            if (!(chunkAccess instanceof LevelChunk chunk)) {
                ExampleMod.LOGGER.warn("[Warehouse] Chunk at ({},{}) is not LevelChunk: {}",
                    chunkPos.x, chunkPos.z, chunkAccess.getClass().getSimpleName());
                return;
            }
            ClientboundLevelChunkWithLightPacket packet =
                new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null);
            sp.connection.send(packet);
            ExampleMod.LOGGER.info("[Warehouse] Pushed chunk ({},{}) to client {}",
                chunkPos.x, chunkPos.z, sp.getName().getString());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to push chunk to client at {}", pos, t);
        }
    }

    /**
     * 单独把目标 BlockEntity 数据推给客户端.
     * <p>
     * 即使 chunk 包成功送达, Mekanism 等 mod 的 createMenu 流程会立即调
     * {@code level.getBlockEntity(pos)}, 客户端 ChunkLevel 内的 blockEntity map
     * 那一刻可能还没填上, 这个包强制覆盖.
     */
    private static void sendBlockEntityToClient(ServerPlayer sp, ServerLevel level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                ExampleMod.LOGGER.warn("[Warehouse] No BlockEntity at {} to push", pos);
                return;
            }
            ClientboundBlockEntityDataPacket bePacket = ClientboundBlockEntityDataPacket.create(be);
            if (bePacket == null) {
                ExampleMod.LOGGER.warn("[Warehouse] create() returned null for BlockEntity at {}", pos);
                return;
            }
            sp.connection.send(bePacket);
            ExampleMod.LOGGER.info("[Warehouse] Pushed BlockEntity at {} to client {}",
                pos, sp.getName().getString());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to push BlockEntity to client", pos, t);
        }
    }

    /** 包装一层 MenuProvider, 让标题显示玩家填的名字. */
    private static MenuProvider wrapForCustomName(MenuProvider original, String customName) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                if (customName != null) {
                    return Component.literal(customName);
                }
                return original.getDisplayName();
            }
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                // 直接转发, 不改任何东西. stillValid 由 BaseContainerBlockEntityMixin
                // 和 ServerPlayerMenuMixin 兜底, 远距离不会关.
                return original.createMenu(id, inv, p);
            }
        };
    }

    /**
     * 延迟 N tick 后用 {@code ServerPlayer.openMenu} 打开菜单.
     * <p>
     * NeoForge 1.21.1 没有 {@code NetworkHooks} 类, 取而代之的是 {@code IPlayerExtension}
     * 提供的 {@code openMenu(MenuProvider, Consumer<RegistryFriendlyByteBuf>)} 扩展方法
     * (混合到 Player 基类), 它走 {@code AdvancedOpenScreenPayload} S2C 通道.
     * <p>
     * 为什么用递归 {@code srv.execute()} 而不是 Thread.sleep:
     * <ul>
     *   <li>Minecraft 的 tick 都在主线程, srv.execute 入队的 Runnable 也是主线程</li>
     *   <li>这样 5 tick 之后执行时, 服务端网络线程已经 flush 了前面发的 chunk + BE 包</li>
     *   <li>客户端也大概率收到了并 addLoadedChunk + deserialize</li>
     * </ul>
     */
    private static void scheduleOpenScreen(ServerPlayer sp, MenuProvider provider, BlockPos pos, int ticksLeft) {
        if (sp.hasDisconnected()) return;
        MinecraftServer srv = sp.server;
        if (ticksLeft <= 0) {
            // 关键: 用 NeoForge 的 IPlayerExtension.openMenu, 走 AdvancedOpenScreenPayload
            // S2C 通道, 跨模组兼容好. 不要再用 vanilla 的 ServerPlayer.openMenu(provider),
            // 它没法传额外数据 (pos).
            sp.openMenu(provider, buf -> buf.writeBlockPos(pos));
            ExampleMod.LOGGER.info("[Warehouse] Opened remote container at {} for {}",
                pos, sp.getName().getString());
            return;
        }
        srv.execute(() -> scheduleOpenScreen(sp, provider, pos, ticksLeft - 1));
    }
}

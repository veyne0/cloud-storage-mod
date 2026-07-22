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
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
                // 容器方块已经被破坏 (e.g. 玩家挖了它), 不再保活该 chunk, 摘 ticket
                com.example.mymod.warehouse.LinkedChunkLoader.remove(payload.linkId);
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u90a3\u4e2a\u4f4d\u7f6e\u5df2\u7ecf\u6ca1\u6709\u65b9\u5757\u4e86, \u5df2\u65ad\u5f00\u8fde\u63a5"));
                data.removeLink(payload.linkId);
                WarehouseDataManager.setDirty();
                WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                return;
            }

            // ---- 自动检测: BE 类型 vs 实际方块 是否兼容 ----
            // 1.21.1 改了 BlockEntityType.create 的行为: block 不在 validBlocks 集合里时
            // 不再 return null, 而是抛 IllegalArgumentException
            //   "Incorrect block entity at X expected to find Y"
            // 这个异常会沿 ClientboundBlockEntityDataPacket 处理链冒泡, 客户端断线.
            //
            // 这里在 push BE 之前用反射检查 validBlocks, 一旦发现不匹配 (例如
            // 玩家把那块方块挖了/换成了别的方块, 或者 chunk 状态不一致):
            //   1. 自动把 link 标记为"高级容器" (175 格距离限制, maxSafeDistance=175)
            //   2. 跳过 sendBlockEntityToClient, 让 LevelMixin 的 stub BE 兜底
            //   3. mod 自己的 screen 仍能打开 (它走 useWithoutItem / 自定义 MenuProvider),
            //      至少 mod 特殊 UI 不渲染但游戏不会崩
            //
            // 这样玩家下次再点这个图标时, maxSafeDistance 限制会先在 step "距离检查" 那里
            // 提示"距离过远", 玩家靠近后再开. 不会再次触发崩溃.
            boolean beIncompatible = !isBlockCompatibleWithBE(be);
            if (beIncompatible) {
                if (!link.isAdvanced()) {
                    link.setAdvanced(true);
                    WarehouseDataManager.setDirty();
                    WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                    sp.sendSystemMessage(Component.literal(
                        "\u00a76[\u4e91\u5b58\u50a8] \u68c0\u6d4b\u5230\u5bb9\u5668 \"" + link.name()
                            + "\" \u7684\u65b9\u5757\u7c7b\u578b\u4e0e BE \u4e0d\u5339\u914d\uff0c\u5df2\u81ea\u52a8\u6807\u8bb0\u4e3a\u9ad8\u7ea7\u5bb9\u5668\uff08"
                            + link.maxSafeDistance() + " \u683c\u8ddd\u79bb\u9650\u5236\uff09\u3002"
                            + "\u8bf7\u9760\u8fd1\u540e\u53f3\u952e\u3002"));
                    ExampleMod.LOGGER.warn(
                        "[Warehouse] Auto-marked link '{}' (block={}) as advanced: BE type {} not compatible with block {} at {}",
                        link.name(), link.blockId(), be.getType(), be.getBlockState().getBlock(), pos);
                }
            }

            // ---- 距离检查: 防止远距离 mod 容器打开时客户端 IllegalStateException 崩盘 ----
            // 部分 mod 容器 (Mekanism 发电机, Create 各种动力机械等) 在极远距离 (~180 格以上)
            // 客户端收到 openScreen 时会做 tile lookup, 找不到真实 BE 就会抛
            // "Client could not locate tile" 直接断线. 服务端在派单前先量距离, 超过
            // link.maxSafeDistance() 就拒掉并提示, 客户端根本不会收到 openScreen.
            // 玩家每次成功在更远距离打开时, maxSafeDistance 单调递增 (见 recordSuccessfulOpen).
            //
            // 新行为: maxSafeDistance = 0 表示"不限距离" (普通原版容器), 不会拒.
            // 高级容器 (Mekanism/Create/...) 的 maxSafeDistance = 175, 超了就用专用消息提示.
            double distSq = sp.blockPosition().distSqr(pos);
            int distance = (int) Math.sqrt(distSq);
            if (link.maxSafeDistance() > 0 && distance > link.maxSafeDistance()) {
                String msg;
                if (link.isAdvanced()) {
                    // 高级容器: 明确告诉玩家这是 mod 容器 + 175 限制原因
                    msg = "\u00a7c[\u4e91\u5b58\u50a8] \u6b64\u5bb9\u5668\u4e3a\u9ad8\u7ea7\u5bb9\u5668"
                        + "\uff0c\u53ea\u80fd\u5728\u79bb\u5bb9\u5668 " + link.maxSafeDistance() + " \u683c\u8ddd\u79bb\u5185\u6253\u5f00"
                        + "\uff08\u5f53\u524d " + distance + " \u683c\uff09\u3002"
                        + "\u8bf7\u9760\u8fd1\u540e\u53f3\u952e\u3002";
                } else {
                    // 普通容器被限制 (玩家手动调过 maxSafeDistance): 用通用消息
                    msg = "\u00a7e[\u4e91\u5b58\u50a8] \u8ddd\u79bb\u8fc7\u8fdc (\u5f53\u524d " + distance
                        + " \u683c, \u6700\u8fdc\u53ef\u8fbe " + link.maxSafeDistance() + " \u683c). "
                        + "\u8bf7\u9760\u8fd1\u540e\u53f3\u952e, \u6210\u529f\u540e\u4f1a\u81ea\u52a8\u63d0\u9ad8\u4e0a\u9650.";
                }
                sp.sendSystemMessage(Component.literal(msg));
                ExampleMod.LOGGER.info("[Warehouse] Refused open: player {} at {} ({} blocks) > maxSafe {} for link {} (advanced={})",
                    sp.getName().getString(), sp.blockPosition(), distance, link.maxSafeDistance(), link.name(), link.isAdvanced());
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
                if (r.consumesAction()) {
                    // useWithoutItem 成功打开了 GUI (例如附魔台, 工作台). 距离记录照样算.
                    if (distance > link.maxSafeDistance()) {
                        link.recordSuccessfulOpen(distance);
                        WarehouseDataManager.setDirty();
                        WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                        ExampleMod.LOGGER.info("[Warehouse] Extended maxSafeDistance via useWithoutItem for '{}' to {} blocks",
                            link.name(), link.maxSafeDistance());
                    }
                    return;
                }
                String name = be.getBlockState().getBlock().getName().getString();
                sp.sendSystemMessage(Component.literal(
                    "\u00a7e[\u4e91\u5b58\u50a8] \u65e0\u6cd5\u8fde\u63a5\u5230 \"" + name + "\". \u8be5\u65b9\u5757\u6ca1\u6709\u53ef\u7528\u7684\u5bb9\u5668\u63a5\u53e3\uff0c\u8bf7\u9760\u8fd1\u540e\u53f3\u952e\u9a8c\u8bc1."));
                return;
            }

            // ---- 准备: 强加载 + 推 chunk + 推 BE ----
            forceLoadChunk(level, pos);
            sendChunkToClient(sp, level, pos);
            // 关键: BE 类型跟 block 不兼容时, 跳过 push, 让 LevelMixin 的 stub BE 兜底.
            // 客户端收到 openScreen 后, level.getBlockEntity(pos) 拿到 stub (Furnace),
            // mod 自己的 screen 至少能开, 不会因为 "Incorrect block entity" 异常被踢回服务器列表.
            if (!beIncompatible) {
                sendBlockEntityToClient(sp, level, pos);
            } else {
                ExampleMod.LOGGER.info(
                    "[Warehouse] Skipped pushing BlockEntity for link '{}': type {} incompatible with block {} at {}",
                    link.name(), be.getType(), be.getBlockState().getBlock(), pos);
            }

            // ---- 包装一层: 用玩家填的名字 ----
            String customName = (link.name() == null || link.name().isEmpty()) ? null : link.name();
            // ---- 关键: 拿到 BE 的 ContainerData 引用, 用于后续主动同步 container data.
            //    vanilla 的 ClientboundContainerSetDataPacket 不会自动发给视距外的玩家.
            //
            // 注意 1.21.1 API 陷阱: 熔炉/烟熏炉/高炉/酿造台/信标 这些 BE 都有
            //     {@code protected final ContainerData dataAccess} 字段, 但 <b>它们并不
            //     implements ContainerData</b> (Mojang 故意这样设计, 字段只是个匿名内部类实现).
            //     所以 {@code be instanceof ContainerData} 永远是 false, 拿不到 dataAccess.
            // 解决: 用反射在 BE 类层级里找 {@code dataAccess} 字段, 类型必须是 ContainerData.
            // 普通箱子/桶/潜影盒 等没有 dataAccess 字段的, 返回 null 即可.
            ContainerData beContainerData = extractContainerData(be);
            if (beContainerData != null) {
                ExampleMod.LOGGER.info("[Warehouse] Extracted ContainerData (count={}) from {} via reflection",
                    beContainerData.getCount(), be.getClass().getSimpleName());
            }
            MenuProvider wrapped = wrapForCustomName(provider, customName, beContainerData);

            // ---- 距离记录: 成功开屏 (包括 useWithoutItem fallback) 后, 把 maxSafeDistance
            //     单调递增到当前距离, 这样玩家能逐步扩大可开范围.
            //     重要: 必须在 scheduleOpenScreen 之前就更新, 因为开屏是异步延迟的, 中间
            //     时间玩家可能退出, 我们希望数据立刻落盘.
            boolean distanceExtended = (distance > link.maxSafeDistance());
            if (distanceExtended) {
                link.recordSuccessfulOpen(distance);
                WarehouseDataManager.setDirty();
                // 把更新后的 maxSafeDistance 同步给客户端 (用于 UI 显示, 可选)
                WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                ExampleMod.LOGGER.info("[Warehouse] Extended maxSafeDistance for '{}' to {} blocks",
                    link.name(), link.maxSafeDistance());
            }

            // ---- 延迟 N tick 后开屏, 开屏后才启动 sync 任务 ----
            // 客户端接收顺序: chunk 包 → BE 包 → (5 tick 后) openScreen 包
            // 客户端 MultiPlayerGameMode.handleOpenScreen 在收到 openScreen 时
            // 会调 level.getBlockEntity(pos), 此时 chunk + BE 都已经就绪.
            // 万一时序不稳, LevelMixin 提供 stub BE 兜底, 永不崩.
            // sync 任务必须延后到开屏之后再启动 — 否则 5 tick 延迟里, sp.containerMenu
            // 还是 inventoryMenu, 任务第一次自检就退出 (因为 containerMenu == inventoryMenu),
            // 永远等不到玩家真正开屏.
            scheduleOpenScreen(sp, wrapped, pos, OPEN_DELAY_TICKS,
                () -> startPeriodicBESync(sp, level, pos, link.linkId(), beContainerData));

            // ---- (旧位置, 已挪到 onOpen 回调里) ----
            // 让远程打开的 BE 数据 (e.g. Mekanism 发电机的能量, 熔炉的火焰/进度) 在客户端
            // 实时更新. 任务逻辑:
            //   * Container data (int 数组, 流量小): 每 1 tick 推一次, 客户端 20 fps 流畅动画
            //   * BE NBT (可能几 KB, 流量大): 每 10 tick 推一次, 让物品/能量等"慢变"字段跟上
            // 退出条件:
            //   1. 玩家断线
            //   2. 玩家主动关了容器 (containerMenu == inventoryMenu)
            //   3. 容器被破坏 (BE null)
            //   4. 达到 BE_SYNC_MAX_TICKS (8 小时) 上限
            // 这样玩家开着 Mekanism 机器的 GUI 时, 能量条/功率数字会跟着服务端 tick 变,
            // 而不再是连接瞬间的"冻结画面".
            //
            // 关键: 不要再手动 ticker.tick() — chunk 已经被 LinkedChunkLoader 永久加载,
            // 服务端在 chunk tick 阶段已经会调 ticker. 我们手动再调一次等于双重 tick,
            // 熔炉进度会走 2 倍速, 瞬间烧完.
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

    /** 强加载 chunk + 强制 tick (server tick 内有效, 关屏后失效).
     *  <p>
     *  关键 1: distance=2 让 ticket level = 33-2 = 31 = ENTITY_TICKING, chunk 才会被 tick.
     *  关键 2: forceTicks=true 让 chunk 持续被识别为 "需要 tick".
     *  详见 {@code LinkedChunkLoader} 类注释.
     */
    private static void forceLoadChunk(ServerLevel level, BlockPos pos) {
        try {
            ChunkPos chunkPos = new ChunkPos(pos);
            level.getChunk(chunkPos.x, chunkPos.z);
            level.getChunkSource().addRegionTicket(
                TicketType.FORCED,
                chunkPos,
                2,           // distance=2 → level=31=ENTITY_TICKING
                chunkPos,
                true         // forceTicks=true
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
            // 用 debug 而不是 info: 周期性 BE sync 每 10 tick 推一次, 开屏 8 小时
            // 会累计 57600 行, info 级别会把 latest.log 撑到几十 MB, debug 级别
            // 默认不输出, 需要排查时再开.
            ExampleMod.LOGGER.debug("[Warehouse] Pushed BlockEntity at {} to client {}",
                pos, sp.getName().getString());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to push BlockEntity to client", pos, t);
        }
    }

    /** 包装一层 MenuProvider, 让标题显示玩家填的名字. */
    private static MenuProvider wrapForCustomName(MenuProvider original, String customName, ContainerData containerData) {
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
                AbstractContainerMenu menu = original.createMenu(id, inv, p);
                // ---- 关键修复: 远程打开时主动同步 container data ----
                // Minecraft 走 vanilla ServerPlayer.openMenu() 时, broadcastChanges() 是
                // 每 tick 调一次, 会发 ClientboundContainerSetDataPacket 给该玩家. 但这只对
                // "视距内" 玩家自动生效 (Minecraft 1.21.1 默认行为), 远距离打开时客户端
                // 永远收不到这种 packet, 导致熔炉的火焰/进度/酿造进度都显示为 0.
                // 解决: 拿到 BE 的 ContainerData 接口, 在 createMenu 之后立刻手动发一次,
                // 后续 BE 同步任务 (startPeriodicBESync) 每 1 tick 再补一次, 客户端
                // 看到的就是 20 fps 的流畅动画.
                if (containerData != null && p instanceof ServerPlayer sp) {
                    int slotCount = containerData.getCount();
                    for (int i = 0; i < slotCount; i++) {
                        sp.connection.send(new ClientboundContainerSetDataPacket(id, i, containerData.get(i)));
                    }
                    ExampleMod.LOGGER.info("[Warehouse] Synced {} container data slot(s) on open (menu={})",
                        slotCount, menu.getClass().getSimpleName());
                }
                return menu;
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
    private static void scheduleOpenScreen(ServerPlayer sp, MenuProvider provider, BlockPos pos, int ticksLeft, Runnable onOpen) {
        if (sp.hasDisconnected()) return;
        MinecraftServer srv = sp.server;
        if (ticksLeft <= 0) {
            // 关键: 用 NeoForge 的 IPlayerExtension.openMenu, 走 AdvancedOpenScreenPayload
            // S2C 通道, 跨模组兼容好. 不要再用 vanilla 的 ServerPlayer.openMenu(provider),
            // 它没法传额外数据 (pos).
            sp.openMenu(provider, buf -> buf.writeBlockPos(pos));
            ExampleMod.LOGGER.info("[Warehouse] Opened remote container at {} for {}",
                pos, sp.getName().getString());
            // 开屏后才启动 sync 任务 — 否则任务在 5 tick 延迟里会自检到
            // containerMenu == inventoryMenu, 立刻自尽, 白启动.
            if (onOpen != null) onOpen.run();
            return;
        }
        srv.execute(() -> scheduleOpenScreen(sp, provider, pos, ticksLeft - 1, onOpen));
    }

    /** Container data 同步间隔 (tick). 1 = 每帧 20 次/秒, 客户端火焰/进度/箭头
     *  是 100% 流畅动画. 流量: 熔炉 4 int × 4 bytes = 16 bytes/packet, 加上包头约 24 bytes,
     *  ~480 bytes/秒, 可以忽略不计. */
    private static final int CONTAINER_DATA_INTERVAL_TICKS = 1;
    /** BE NBT 同步间隔 (tick). 10 = 0.5 秒, BE NBT 包可能几 KB, 不能每帧推. 0.5 秒
     *  同步一次足够让玩家看到物品/能量等"慢变"的字段更新. */
    private static final int BE_NBT_INTERVAL_TICKS = 10;
    /** BE 同步最大连续时长 (tick). 8 小时 (576000 tick) 上限, 防止玩家挂机一晚耗光带宽. */
    private static final int BE_SYNC_MAX_TICKS = 576000;

    /**
     * 反射拿 BE 的 {@code dataAccess} 字段 (类型为 {@link ContainerData}).
     * <p>
     * 为什么需要反射: 1.21.1 vanilla 的 {@code AbstractFurnaceBlockEntity},
     * {@code BrewingStandBlockEntity}, {@code BeaconBlockEntity} 这些"按状态机运转"的容器
     * BE, 它们的 {@code dataAccess} 是个 {@code protected final ContainerData} 字段,
     * 但 <b>BE 类本身不 implements {@code ContainerData}</b> (Mojang 故意这样设计 —
     * 字段是匿名内部类, 只暴露给菜单, 不让外部直接通过 instanceof 拿到).
     * 所以 {@code be instanceof ContainerData} 永远 false, 拿不到这个字段.
     * <p>
     * 反射流程: 从 BE 的 Class 开始, 沿 superclass 一路往上找 {@code dataAccess} 字段,
     * 找到后 setAccessible(true) 读出来, 类型必须是 {@link ContainerData} 才能用.
     *
     * @return BE 的 dataAccess 引用; 没找到 / 字段类型不匹配 / 读失败都返回 null
     */
    private static ContainerData extractContainerData(BlockEntity be) {
        if (be == null) return null;
        Class<?> current = be.getClass();
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField("dataAccess");
                f.setAccessible(true);
                Object value = f.get(be);
                if (value instanceof ContainerData cd) {
                    return cd;
                }
            } catch (NoSuchFieldException ignored) {
                // 当前类没有 dataAccess, 沿 superclass 继续找
            } catch (Throwable t) {
                ExampleMod.LOGGER.debug("[Warehouse] extractContainerData failed on {}: {}",
                    current.getSimpleName(), t.toString());
                return null;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 反射拿 {@code BlockEntityType.validBlocks} 字段 (Set&lt;Block&gt;), 判断 BE 类型
     * 是不是跟当前位置的 block 兼容.
     * <p>
     * <b>为什么需要这个:</b> 1.21.1 的 {@code BlockEntityType.create(pos, state)} 在
     * block 不在 validBlocks 集合里时, <b>抛 IllegalArgumentException</b>
     * {@code "Incorrect block entity at X expected to find Y"}, 而不是像旧版本
     * 那样 return null. 这个异常沿 {@code ClientboundBlockEntityDataPacket} 处理链
     * 冒泡, Minecraft 当成"网络包错误"关闭连接, 玩家被踢回服务器列表.
     * <p>
     * <b>解决方法:</b> 服务端 push BE 之前先用反射检查, 不兼容就:
     * <ol>
     *   <li>自动把 link 标记为"高级容器" (175 格距离限制)</li>
     *   <li>跳过 BE push, 让 {@code LevelMixin} 提供的 stub BE 兜底</li>
     * </ol>
     * 客户端的 {@code BlockEntityTypeMixin} 会再兜底一层 (把异常捕获返回 null),
     * 防止任何遗漏的兼容性问题把玩家踢回服务器列表.
     *
     * @return true = BE 类型跟当前位置的 block 兼容 (可以安全 push);
     *         false = 不兼容, 应该跳过 push;
     *         反射失败 / 字段不存在 / 任何异常都返回 true (假设兼容, 避免误判)
     */
    private static final java.lang.reflect.Field VALID_BLOCKS_FIELD;
    static {
        java.lang.reflect.Field tmp = null;
        try {
            tmp = BlockEntityType.class.getDeclaredField("validBlocks");
            tmp.setAccessible(true);
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn(
                "[Warehouse] Could not access BlockEntityType.validBlocks via reflection: {}",
                t.toString());
        }
        VALID_BLOCKS_FIELD = tmp;
    }

    private static boolean isBlockCompatibleWithBE(BlockEntity be) {
        if (be == null) return true;
        if (VALID_BLOCKS_FIELD == null) return true;
        try {
            @SuppressWarnings("unchecked")
            java.util.Set<net.minecraft.world.level.block.Block> validBlocks =
                (java.util.Set<net.minecraft.world.level.block.Block>) VALID_BLOCKS_FIELD.get(be.getType());
            return validBlocks != null && validBlocks.contains(be.getBlockState().getBlock());
        } catch (Throwable t) {
            ExampleMod.LOGGER.debug(
                "[Warehouse] isBlockCompatibleWithBE failed for {}: {}",
                be.getClass().getSimpleName(), t.toString());
            return true; // 假设兼容, 避免误判
        }
    }

    /**
     * 在远程容器开屏期间, 持续向该玩家推 container data + BE NBT, 让客户端的
     * 熔炉火焰/进度/酿造进度/发电机能量等持续动起来, 而不是连接瞬间的"冻结画面".
     * <p>
     * 退出条件 (任一满足即停):
     * <ul>
     *   <li>玩家断线 ({@code sp.hasDisconnected()})</li>
     *   <li>玩家关了容器 ({@code sp.containerMenu == sp.inventoryMenu})</li>
     *   <li>容器被破坏 ({@code level.getBlockEntity(pos) == null})</li>
     *   <li>达到 {@link #BE_SYNC_MAX_TICKS} 上限</li>
     * </ul>
     *
     * @param containerData BE 的 {@link ContainerData} 接口 (熔炉/高炉/烟熏炉/酿造台/信标 等实现它).
     *                       每 {@link #CONTAINER_DATA_INTERVAL_TICKS} tick 重发一次, 客户端动画
     *                       就跟"站在熔炉旁边"一样流畅. 传 null 表示 BE 不实现 ContainerData
     *                       (普通箱子/桶/潜影盒等).
     *
     * <h3>关于"延迟 N tick"</h3>
     * {@link MinecraftServer#execute(Runnable)} 只是把 runnable 入队到当前 server tick 的任务
     * 队列, <b>下一个 server tick 就执行</b> (1 tick = 50ms), 不会真的等 N tick. 所以
     * "递归 {@code sp.server.execute(this)}"等于"每 1 tick 跑一次". 我们用两个独立计数器
     * 分别累加, 达到各自阈值才推对应包:
     * <ul>
     *   <li>container data: 累加 1, 达到 1 → 推 (每帧 1 次, 流畅动画)</li>
     *   <li>BE NBT: 累加 1, 达到 10 → 推 (每 0.5 秒 1 次, 慢变字段够用)</li>
     * </ul>
     * 跑任务的频率仍是 1 tick 一次 (这本身没消耗, 几个 int 比较而已).
     */
    private static void startPeriodicBESync(ServerPlayer sp, ServerLevel level, BlockPos pos, UUID linkId, ContainerData containerData) {
        Runnable task = new Runnable() {
            int elapsedTicks = 0;
            int dataCounter = 0;
            int nbtCounter = 0;
            @Override public void run() {
                // ---- 退出条件检查 ----
                if (sp.hasDisconnected()) {
                    ExampleMod.LOGGER.info("[Warehouse] BE sync stopped: player {} disconnected (link {})",
                        sp.getName().getString(), linkId);
                    return;
                }
                if (sp.containerMenu == sp.inventoryMenu) {
                    ExampleMod.LOGGER.info("[Warehouse] BE sync stopped: player {} closed remote menu (link {})",
                        sp.getName().getString(), linkId);
                    return;
                }
                if (level.getBlockEntity(pos) == null) {
                    ExampleMod.LOGGER.info("[Warehouse] BE sync stopped: BE at {} removed (link {})",
                        pos, linkId);
                    return;
                }
                if (elapsedTicks >= BE_SYNC_MAX_TICKS) {
                    ExampleMod.LOGGER.info("[Warehouse] BE sync stopped: max duration reached for link {}",
                        linkId);
                    return;
                }
                elapsedTicks++;

                // ---- 1. Container data (熔炉火焰/进度/箭头) — 每帧推, 流畅动画 ----
                // BE 本身已经被 LinkedChunkLoader 永久保活的 chunk 自然 tick 了, 这里的
                // containerData.get(i) 读到的是当前最新的 int 值. 每帧读一次, 每帧推一次.
                if (containerData != null && dataCounter++ >= CONTAINER_DATA_INTERVAL_TICKS) {
                    dataCounter = 0;
                    AbstractContainerMenu menu = sp.containerMenu;
                    if (menu != null && menu != sp.inventoryMenu) {
                        int slotCount = containerData.getCount();
                        for (int i = 0; i < slotCount; i++) {
                            sp.connection.send(new ClientboundContainerSetDataPacket(
                                menu.containerId, i, containerData.get(i)));
                        }
                    }
                }

                // ---- 2. BE NBT (物品/能量等慢变字段) — 每 0.5 秒推一次 ----
                if (nbtCounter++ >= BE_NBT_INTERVAL_TICKS) {
                    nbtCounter = 0;
                    try {
                        sendBlockEntityToClient(sp, level, pos);
                    } catch (Throwable t) {
                        ExampleMod.LOGGER.warn("[Warehouse] BE NBT sync failed at {} (link {}): {}",
                            pos, linkId, t.toString());
                    }
                }

                // ---- 排下一轮: 仍每 1 tick 入队一次, 退出条件/计数器决定是否推包 ----
                sp.server.execute(this);
            }
        };
        sp.server.execute(task);
        ExampleMod.LOGGER.info("[Warehouse] BE sync started for link {} (data={}t, nbt={}t, max={}t)",
            linkId, CONTAINER_DATA_INTERVAL_TICKS, BE_NBT_INTERVAL_TICKS, BE_SYNC_MAX_TICKS);
    }

    /**
     * 保留为 noop — chunk 已经被 {@link com.example.mymod.warehouse.LinkedChunkLoader}
     * 永久保活, 服务端 chunk tick 阶段会自动调 ticker, 这里再调一次就是双重 tick,
     * 会让熔炉瞬间烧完. 保留这个空方法只是为防止别处误调.
     */
    @SuppressWarnings({"unchecked", "rawtypes", "unused"})
    private static void manualTickBE(ServerLevel level, BlockPos pos) {
        // noop: 双重 tick 会让烧制进度走 2 倍速, 必须禁用.
    }
}

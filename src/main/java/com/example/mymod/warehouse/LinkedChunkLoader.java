package com.example.mymod.warehouse;

import com.example.mymod.ExampleMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端单例: 给每个已连接容器加一个长超时 chunk ticket, 让容器所在 chunk
 * 一直保持加载, 玩家走到天涯海角熔炉也照烧.
 * <p>
 * <b>为什么需要自定义 TicketType:</b>
 * 原版 {@link TicketType#FORCED} 的 timeout 只有 ~50 tick (2.5s), 玩家离开或维度切走
 * 立刻失效. {@code START} 是 spawn 区用的. {@code DRAGON} 是末影龙用的. 这些都不适合
 * "跟随连接关系持续保活" 的场景.
 * <p>
 * 我们用 {@link TicketType#create(String, java.util.Comparator, int)} 自定义一个
 * 超长 timeout (Integer.MAX_VALUE ≈ 3.4 年) 的 ticket, 效果等价于 "永久加载", 但
 * 仍然能通过 {@code removeRegionTicket} 主动摘除 (玩家解除连接 / 容器被破坏).
 * <p>
 * <b>跟 {@code C2SOpenLinkedContainer.forceLoadChunk} 的区别:</b> 那是玩家点图标时
 * 临时加载 (FORCED, 关屏就放); 这个是 "连接后就一直保活", 用于熔炉烧制、
 * Mekanism 发电机充能等需要后台 tick 的场景.
 * <p>
 * 生命周期:
 * <ul>
 *   <li>{@link #add(UUID, ResourceKey, ChunkPos)}: 新连接的容器立刻挂 ticket
 *       + 触发 chunk 加载.</li>
 *   <li>{@link #remove(UUID)}: 玩家解除连接 / 容器被破坏时摘 ticket.</li>
 *   <li>{@link #onLevelLoad}: 维度刚加载完时, 重新挂回所有 active 链接的 ticket
 *       (chunk source 重建后旧 ticket 会失效).</li>
 * </ul>
 */
public final class LinkedChunkLoader {
    private LinkedChunkLoader() {}

    /**
     * 自定义 ticket: 跟 {@link UUID} 关联, timeout 设成 Integer.MAX_VALUE tick
     * (~3.4 年), 实际就是 "直到主动 removeRegionTicket 才失效".
     * <p>
     * key 用 {@link Unit#INSTANCE} (跟 {@code TicketType.START}/{@code DRAGON} 同样的
     * 模式) — 我们用 linkId 维度 (ACTIVE Map) 来管增删, 不需要把 linkId 也存到 ticket
     * 里 (那样的话同一个 linkId 重复 add 会被去重, 反而不利于热重载).
     */
    private static final TicketType<Unit> LINK_TICKET_TYPE =
        TicketType.create("premiumcloudstorage:link_loader", (a, b) -> 0, Integer.MAX_VALUE);

    /** 记录每个 link 的 ticket 位置, 用于 remove + 跨维度时重新挂. */
    private record TicketRecord(ResourceKey<Level> dim, ChunkPos pos) {}
    private static final Map<UUID, TicketRecord> ACTIVE = new HashMap<>();

    /**
     * 注册事件钩子. {@link ExampleMod} 构造时调一次.
     */
    public static void register() {
        NeoForge.EVENT_BUS.register(LinkedChunkLoader.class);
    }

    // ==================== 事件 ====================

    /**
     * 任意维度加载完成时, 重新挂回该维度上所有 active 链接的 ticket.
     * <p>
     * 必要性: 服务端启动时 overworld 第一个加载, 我们的 onLevelLoad 此时拿到
     * ACTIVE 表, 里面是从 saveddata 还原出来的 link. 但这些 link 可能在其他维度
     * (nether / end), 而那些维度还没加载, level == null, 当时挂不上.
     * 玩家传送到该维度触发 LevelEvent.Load 后, 再补一次挂.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        int reattached = 0;
        for (var entry : ACTIVE.entrySet()) {
            TicketRecord rec = entry.getValue();
            if (rec.dim() == sl.dimension()) {
                if (applyAddTicket(entry.getKey(), rec.dim(), rec.pos())) {
                    reattached++;
                }
            }
        }
        if (reattached > 0) {
            ExampleMod.LOGGER.info("[ChunkLoader] Reattached {} PERSISTENT ticket(s) on level load ({})",
                reattached, sl.dimension().location());
        }
    }

    // ==================== 公共 API ====================

    /**
     * 给一条 link 挂超长 timeout ticket + 立刻加载 chunk.
     * <p>
     * 同一 linkId 重复调用是幂等的 (后写覆盖前写).
     *
     * @param linkId 该容器的 link UUID
     * @param dim    容器所在维度
     * @param pos    容器方块位置 (会转成 ChunkPos)
     */
    public static void add(UUID linkId, ResourceKey<Level> dim, net.minecraft.core.BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        ACTIVE.put(linkId, new TicketRecord(dim, chunkPos));
        applyAddTicket(linkId, dim, chunkPos);
    }

    /**
     * 摘掉指定 link 的 ticket, 并从 active 表里删除.
     * 链接不存在或 ticket 早已被服务端清掉时静默 noop.
     */
    public static void remove(UUID linkId) {
        TicketRecord rec = ACTIVE.remove(linkId);
        if (rec == null) return;
        applyRemoveTicket(linkId, rec.dim(), rec.pos());
    }

    /** 当前 active 数量. 给 debug / 测试用. */
    public static int activeCount() {
        return ACTIVE.size();
    }

    // ==================== 内部实现 ====================

    /** 实际调 ServerChunkCache.addRegionTicket. 成功返回 true.
     *
     *  <h3>关键 1: 第 5 个参数 {@code forceTicks=true} 必须传 true</h3>
     *  1.21.1 的 {@code addRegionTicket} 有 4 参数 (默认 {@code forceTicks=false}) 和
     *  5 参数两个重载. 4 参数版本 (false) 只是让 chunk 保持加载, <b>但不 tick</b>.
     *  这就解释了之前玩家看到 "chunk 没卸载, 但熔炉不动" 的现象 — 玩家一离开, 服务端
     *  跳过这个 chunk 的 block tick / random tick / block entity tick, 所以熔炉的
     *  litTime 不递减, 进度不走.
     *  <p>
     *  用 5 参数版本传 {@code true}, chunk source 就会在每 server tick 都把这个
     *  chunk 排进"必须 tick"列表, 熔炉/Mekanism 发电机/Create 机械都能正常工作.
     *
     *  <h3>关键 2: distance 参数必须是 2, 不是 0</h3>
     *  {@code addRegionTicket} 内部用 {@code ChunkLevel.byStatus(FullChunkStatus.FULL) - distance}
     *  作为 ticket level. {@code byStatus(FULL) = 33}. 实际让 chunk <b>entity-ticking</b> 的
     *  level 上限是 31 ({@code ChunkLevel.isEntityTicking(31) = true}). 也就是说:
     *  <ul>
     *    <li>distance=0 → level=33 → chunk <b>只是 loaded, 不 block tick 不 entity tick</b>!
     *        这就是为什么 forceTicks=true 但 BE 仍然不工作 — chunk 加载了, 但 chunk tick
     *        流程不执行. 熔炉 litTime 不递减.</li>
     *    <li>distance=2 → level=31 → chunk entity-ticking → block tick + random tick + BE
     *        tick 全部正常.</li>
     *  </ul>
     *  {@code ChunkMap.FORCED_TICKET_LEVEL = 31 = ChunkLevel.byStatus(ENTITY_TICKING)},
     *  这是 vanilla 内部给"强制 tick" chunk 用的标准 level. 我们用 distance=2 达到同样效果.
     */
    private static boolean applyAddTicket(UUID linkId, ResourceKey<Level> dim, ChunkPos pos) {
        try {
            MinecraftServer server = WarehouseDataManager.getServer();
            if (server == null) return false;
            ServerLevel level = server.getLevel(dim);
            if (level == null) {
                // 维度还没加载, 等 onLevelLoad 再补. ACTIVE 表里已经记好, 不丢.
                ExampleMod.LOGGER.debug("[ChunkLoader] Defer ticket for link {}: level {} not loaded yet",
                    linkId, dim.location());
                return false;
            }
            // distance=2 让 ticket level = 33-2 = 31 = ENTITY_TICKING.
            // forceTicks=true 进一步确保 chunk 在 DistanceManager.shouldForceTicks() 里被识别.
            level.getChunkSource().addRegionTicket(LINK_TICKET_TYPE, pos, 2, Unit.INSTANCE, true);
            // 立即把 chunk 加载出来 (addRegionTicket 不会自动触发加载, 只保活).
            // getChunk 走的是 ChunkStatus.FULL, 会同步等 chunk 加载完, 避免 race condition.
            level.getChunk(pos.x, pos.z);
            ExampleMod.LOGGER.info("[ChunkLoader] Added ENTITY-TICK ticket (level=31) for link {} at chunk ({},{}) in {}",
                linkId, pos.x, pos.z, dim.location());
            return true;
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[ChunkLoader] Failed to add ticket for link {}: {}", linkId, t.toString());
            return false;
        }
    }

    private static void applyRemoveTicket(UUID linkId, ResourceKey<Level> dim, ChunkPos pos) {
        try {
            MinecraftServer server = WarehouseDataManager.getServer();
            if (server == null) return;
            ServerLevel level = server.getLevel(dim);
            if (level == null) return; // 维度已经卸载, ticket 自然没了
            // remove 必须用相同 forceTicks 跟相同 distance 才能精确找到对应 ticket
            level.getChunkSource().removeRegionTicket(LINK_TICKET_TYPE, pos, 2, Unit.INSTANCE, true);
            ExampleMod.LOGGER.info("[ChunkLoader] Removed ticket for link {} at chunk ({},{}) in {}",
                linkId, pos.x, pos.z, dim.location());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[ChunkLoader] Failed to remove ticket for link {}: {}", linkId, t.toString());
        }
    }
}

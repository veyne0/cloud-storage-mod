package com.example.mymod.warehouse;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.network.S2CSyncEntityLinks;
import com.example.mymod.warehouse.network.S2CSyncLinkedContainers;
import com.example.mymod.warehouse.network.WarehouseNetworking;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端单例: 所有在线玩家的 {@link PersonalWarehouseData} 都存在这里, 通过 {@code SavedData} 落盘.
 * <p>
 * 为什么不用 capability? NeoForge 1.21.1 把 capability 重构成了 {@code BlockCapability}/
 * {@code EntityCapability} 那一套, 写起来又臭又长, 而仓库数据本质就是"按玩家 UUID 查表",
 * 直接一个 {@code Map<UUID, PersonalWarehouseData>} + 一份 {@code SavedData} 就够了.
 *
 * <p>生命周期:
 * <ul>
 *   <li>{@link #get(ServerLevel)} — 拿到当前世界的 {@link SavedData} 句柄, 触发加载 (第一次访问时)</li>
 *   <li>{@link #get(ServerPlayer)} — 按 UUID 取玩家的数据, 不存在就 new 一个 (玩家刚加入时)</li>
 *   <li>{@link #setDirty()} 任何修改后调用, 告诉 {@code SavedData} 需要写盘</li>
 * </ul>
 */
public class WarehouseDataManager {
    /** 当前 overworld 维度的 SavedData. 多维度共享一份 (data 跟玩家走, 不跟维度走). */
    private static SavedData SAVED;
    private static ServerLevel SERVER_LEVEL;
    /** 缓存当前维度的 registry provider, 序列化时给 IItemHandler 用. */
    private static HolderLookup.Provider PROVIDER;
    private static final Map<UUID, PersonalWarehouseData> CACHE = new HashMap<>();

    private WarehouseDataManager() {}

    /** 注册事件钩子. 在 {@link ExampleMod} 构造时调一次. */
    public static void register() {
        NeoForge.EVENT_BUS.register(WarehouseDataManager.class);
    }

    // ==================== 事件 ====================

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) {
            SERVER_LEVEL = sl;
            PROVIDER = sl.registryAccess();
            // 1.21.1 签名: computeIfAbsent(Factory<T>, String), 不再需要 ::new 第三个参数
            SAVED = sl.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                    WarehouseSavedData::new,
                    WarehouseSavedData::load),
                "personal_warehouse");
            CACHE.clear();
            for (var entry : ((WarehouseSavedData) SAVED).players.entrySet()) {
                CACHE.put(entry.getKey(), entry.getValue());
            }
            ExampleMod.LOGGER.info("[Warehouse] SavedData loaded: {} player(s) registered.", CACHE.size());

            // 给所有玩家已连接的容器挂 PERSISTENT chunk ticket, 让熔炉/机器照常 tick.
            // 这里只挂 overworld 的; 跨维度的会在对应维度的 onLevelLoad 里补挂
            // (LinkedChunkLoader 自己处理).
            for (var pdata : CACHE.values()) {
                for (var link : pdata.getLinkedContainers()) {
                    LinkedChunkLoader.add(link.linkId(), link.dimension(), link.pos());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl && sl.dimension() == Level.OVERWORLD) {
            CACHE.clear();
            SAVED = null;
            SERVER_LEVEL = null;
            PROVIDER = null;
            // overworld 卸载时整个 ACTIVE 表清空 (下次启动会从 saveddata 还原).
            // 注意: LinkedChunkLoader 没有 clearAll, 因为 ACTIVE 本身是它的"源真理",
            // 但服务端关停时全维度都卸载了, ticket 自然没了, 下次启动时 onLevelLoad
            // 重新 add 一次即可. 这里我们显式清, 避免重载 (e.g. /reload) 时出现 stale 记录.
            // 暂不实现 clearAll, 因为卸载时 ticket 也没了, 留 ACTIVE 等下次启动自然重新挂.
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 玩家加入时, CACHE 里没有就立刻建一个空数据, 并把"已连接容器"列表
        // 同步到客户端, 这样客户端进游戏后, 仓库右边的图标就直接显示, 不需要
        // 玩家再点一次"重新连接"才能看见.
        if (event.getEntity() instanceof ServerPlayer sp) {
            PersonalWarehouseData data = get(sp);
            sendSyncLinkedContainers(sp, data);
            sendSyncEntityLinks(sp, data);
            com.example.mymod.warehouse.network.S2CSyncUpgradeState.sendTo(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerCloned(PlayerEvent.Clone event) {
        // 玩家死亡复活: 保留同一个 UUID 的数据, 不重置
        if (event.getEntity() instanceof ServerPlayer sp) {
            get(sp); // ensure exists
        }
    }

    // ==================== 公共 API ====================

    /** 取一个玩家当前的数据. 不存在就 new 一个, 同时写回 cache. */
    public static PersonalWarehouseData get(ServerPlayer player) {
        ensureLoaded();
        return CACHE.computeIfAbsent(player.getUUID(), k -> new PersonalWarehouseData());
    }

    /** 直接按 UUID 取 (给发包时用, 不需要 ServerPlayer 实例). */
    public static PersonalWarehouseData get(UUID uuid) {
        ensureLoaded();
        return CACHE.computeIfAbsent(uuid, k -> new PersonalWarehouseData());
    }

    /** 任何修改数据之后调一下, 告诉 SavedData 写盘. */
    public static void setDirty() {
        if (SAVED != null) SAVED.setDirty();
    }

    /**
     * 拿到当前 {@link MinecraftServer} 句柄 (overworld 加载时设置).
     * <p>
     * 给 {@link LinkedChunkLoader} 等需要跨维度访问 {@code ServerLevel} 的模块用.
     * 还没初始化完成 (例如在 client 端 / mod 启动早期) 时返回 null.
     */
    public static MinecraftServer getServer() {
        return SERVER_LEVEL != null ? SERVER_LEVEL.getServer() : null;
    }

    /**
     * 把 {@code data} 里所有已连接容器的"第一页前 9 个物品"填好 (鼠标悬停图标时用),
     * 然后发给 {@code sp}. 这是仓库模组各发包点统一调用的入口 —— 直接
     * {@code new S2CSyncLinkedContainers(data.getLinkedContainers())} 不会填 preview.
     */
    public static void sendSyncLinkedContainers(ServerPlayer sp, PersonalWarehouseData data) {
        LinkedContainerPreview.fillPreviews(sp.serverLevel(), data.getLinkedContainers());
        WarehouseNetworking.sendTo(sp, new S2CSyncLinkedContainers(data.getLinkedContainers()));
    }

    /**
     * 同步收容的实体列表到客户端 (在 V 键开仓库 / 任何实体增删时调用).
     * <p>
     * 客户端 {@code ClientWarehouseCache} 收到后更新缓存, 仓库 UI 重新画实体图标.
     */
    public static void sendSyncEntityLinks(ServerPlayer sp, PersonalWarehouseData data) {
        WarehouseNetworking.sendTo(sp, new S2CSyncEntityLinks(data.getEntityLinks()));
    }

    private static void ensureLoaded() {
        if (SAVED == null) {
            throw new IllegalStateException("WarehouseDataManager used before overworld loaded!");
        }
    }

    // ==================== SavedData 内部类 ====================

    public static class WarehouseSavedData extends SavedData {
        public final Map<UUID, PersonalWarehouseData> players = new HashMap<>();

        /** 1.21.1 签名: BiFunction<CompoundTag, HolderLookup.Provider, T>. */
        public static WarehouseSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
            WarehouseSavedData d = new WarehouseSavedData();
            CompoundTag playersTag = tag.getCompound("players");
            for (String key : playersTag.getAllKeys()) {
                try {
                    UUID id = UUID.fromString(key);
                    d.players.put(id, PersonalWarehouseData.fromNbt(playersTag.getCompound(key), provider));
                } catch (Exception ignored) { /* 跳过损坏的条目 */ }
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            CompoundTag playersTag = new CompoundTag();
            // 注意: 这里要写 CACHE (内存里最新的), 而不是 this.players
            // 因为 CACHE 可能比 this.players 多/少/不同
            for (var entry : CACHE.entrySet()) {
                playersTag.put(entry.getKey().toString(), entry.getValue().toNbt(provider));
            }
            tag.put("players", playersTag);
            return tag;
        }
    }
}

package com.example.mymod.warehouse.network;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.WarehouseDataManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * 客户端 -> 服务端: 玩家在仓库界面点了一个"已连接容器"图标, 请服务端打开它.
 * <p>
 * 核心策略: 始终打开容器方块自带的原版/原模组界面.
 * <p>
 * 打开顺序 (按优先级):
 * <ol>
 *   <li>{@code BlockState.getMenuProvider(level, pos)} - 标准 API</li>
 *   <li>反射 fallback: 找 BlockEntity.getMenuProvider() (部分 mod 用 legacy API)</li>
 * </ol>
 * <p>
 * 距离无限制由四重保险保证:
 * <ol>
 *   <li>强加载容器所在 chunk + region ticket</li>
 *   <li>临时拉大玩家的 BLOCK_INTERACTION_RANGE</li>
 *   <li>反射改 menu 的 access 字段</li>
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
    private static final double FAR_INTERACT_RANGE = 64.0;
    private static final ResourceLocation FAR_INTERACT_ID =
        ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "far_interact_modifier");

    public static final IPayloadHandler<C2SOpenLinkedContainer> HANDLER = (payload, ctx) -> {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var data = WarehouseDataManager.get(sp);
            var link = data.findLink(payload.linkId);
            if (link == null) return;
            if (sp.level().dimension() != link.dimension()) {
                sp.sendSystemMessage(Component.literal(
                    "\u00a7c[\u4e91\u5b58\u50a8] \u5bb9\u5668\u5728\u53e6\u4e00\u4e2a\u7ef4\u5ea6, \u8bf7\u5148\u53bb " + link.dimension().location() + " \u518d\u6253\u5f00"));
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
            forceLoadChunk(level, pos);
            applyFarInteractionModifier(sp);
            MenuProvider original = be.getBlockState().getMenuProvider(level, pos);
            if (original == null) {
                original = findMenuProviderViaReflection(be);
                if (original != null) {
                    ExampleMod.LOGGER.info("[Warehouse] Found MenuProvider via reflection on {}",
                        be.getClass().getSimpleName());
                }
            }
            if (original == null) {
                ExampleMod.LOGGER.info("[Warehouse] No MenuProvider for {} at {}; simulating right-click",
                    be.getBlockState().getBlock().getName().getString(), pos);
                BlockHitResult hitResult = new BlockHitResult(
                    Vec3.atCenterOf(pos), Direction.UP, pos, false);
                InteractionResult r = level.getBlockState(pos).useWithoutItem(level, sp, hitResult);
                if (r.consumesAction()) {
                    ExampleMod.LOGGER.info("[Warehouse] Simulated right-click succeeded for {}", pos);
                } else {
                    String name = be.getBlockState().getBlock().getName().getString();
                    sp.sendSystemMessage(Component.literal(
                        "\u00a7e[\u4e91\u5b58\u50a8] \"" + name + "\" \u627e\u4e0d\u5230\u5bb9\u5668\u63a5\u53e3, \u8bf7\u9760\u8fd1\u540e\u53f3\u952e."));
                }
                return;
            }
            String customName = (link.name() == null || link.name().isEmpty()) ? null : link.name();
            sp.openMenu(wrapForRemoteOpen(original, sp, customName));
        });
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

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

    private static void applyFarInteractionModifier(ServerPlayer sp) {
        try {
            AttributeInstance inst = sp.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (inst == null) {
                ExampleMod.LOGGER.warn("[Warehouse] Player has no BLOCK_INTERACTION_RANGE attribute");
                return;
            }
            inst.removeModifier(FAR_INTERACT_ID);
            AttributeModifier mod = new AttributeModifier(
                FAR_INTERACT_ID,
                FAR_INTERACT_RANGE,
                AttributeModifier.Operation.ADD_VALUE
            );
            inst.addPermanentModifier(mod);
            ExampleMod.LOGGER.info("[Warehouse] Applied far-interact modifier to {} (now {} blocks)",
                sp.getName().getString(), inst.getValue());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to apply far-interact modifier", t);
        }
    }

    public static void removeFarInteractionModifier(ServerPlayer sp) {
        try {
            AttributeInstance inst = sp.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (inst == null) return;
            inst.removeModifier(FAR_INTERACT_ID);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static Field findAccessField(Class<?> menuClass) {
        Class<?> current = menuClass;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField("access");
                if (f.getType() == ContainerLevelAccess.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
                // continue
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static MenuProvider wrapForRemoteOpen(MenuProvider original, ServerPlayer sp, String customName) {
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
                AbstractContainerMenu menu = original.createMenu(id, inv, p);
                if (menu != null) {
                    tryOverrideAccess(menu, p);
                }
                return menu;
            }
        };
    }

    private static void tryOverrideAccess(AbstractContainerMenu menu, Player p) {
        try {
            Field f = findAccessField(menu.getClass());
            if (f == null) {
                ExampleMod.LOGGER.warn("[Warehouse] No 'access' field on {} (or any superclass); menu may close at distance.",
                    menu.getClass().getName());
                return;
            }
            ContainerLevelAccess safe = new ContainerLevelAccess() {
                @Override
                public <T> Optional<T> evaluate(BiFunction<Level, BlockPos, T> function) {
                    try {
                        return Optional.ofNullable(function.apply(p.level(), p.blockPosition()));
                    } catch (Throwable t) {
                        return Optional.empty();
                    }
                }
            };
            f.set(menu, safe);
            ExampleMod.LOGGER.info("[Warehouse] Override access field on {} (declared in {})",
                menu.getClass().getSimpleName(), f.getDeclaringClass().getSimpleName());
        } catch (Throwable t) {
            ExampleMod.LOGGER.warn("[Warehouse] Failed to override access field on {}", menu.getClass().getName(), t);
        }
    }
}

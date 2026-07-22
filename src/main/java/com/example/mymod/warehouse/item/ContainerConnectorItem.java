package com.example.mymod.warehouse.item;

import com.example.mymod.ExampleMod;
import com.example.mymod.warehouse.LinkedChunkLoader;
import com.example.mymod.warehouse.LinkedContainer;
import com.example.mymod.warehouse.PersonalWarehouseData;
import com.example.mymod.warehouse.WarehouseDataManager;
import com.example.mymod.warehouse.network.S2COpenNameEntry;
import com.example.mymod.warehouse.network.S2CSyncLinkedContainers;
import com.example.mymod.warehouse.network.WarehouseNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.List;
import java.util.UUID;

/**
 * 容器连接器: 对箱子/桶/潜影盒等容器右键 → 把容器登记到玩家云存储的"已连接列表".
 * <p>
 * 行为:
 * <ul>
 *   <li>右键未连接的存储容器 → 创建一个新 {@link LinkedContainer}, 弹命名框</li>
 *   <li>右键已连接过的容器 (潜行) → 断开连接</li>
 *   <li>右键已连接过的容器 (不潜行) → 提示"已经连接过"</li>
 *   <li>右键非存储容器 → 静默无操作</li>
 * </ul>
 * 物品本身不消耗, 可以一直用.
 * <p>
 * 配方: 红石 + 铁锭 (shapeless, 见 data/examplemod/recipe/container_connector.json).
 */
public class ContainerConnectorItem extends Item {
    public ContainerConnectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        // 描述: "可以连接大部分有存储功能的容器, 连接后可以在仓库界面突破距离限制打开容器"
        tooltip.add(Component.literal("§7可以连接大部分有存储功能的容器,"));
        tooltip.add(Component.literal("§7连接后可以在仓库界面"));
        tooltip.add(Component.literal("§7突破距离限制打开容器"));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS; // 让服务端处理
        Player player = ctx.getPlayer();
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return InteractionResult.PASS;

        // 允许连接的条件 (满足任一即可):
        //   1. ItemHandler capability 有 slot (典型: 箱子/桶/潜影盒/熔炉/漏斗等)
        //   2. 实现 MenuProvider (有 GUI 但没暴露 ItemHandler, 如 Mekanism 机器、
        //      各种 mod 设备)
        //   3. 任何有 BlockEntity 的方块 — 兜底连接, 打开时服务端会模拟 useWithoutItem
        //      触发原版/原 mod 的 GUI 逻辑 (附魔台/工作台/切石机/织布机等)
        //
        // 之前只看 ItemHandler + MenuProvider, 导致附魔台/工作台/切石机这些
        // "BlockState.getMenuProvider 返回 null, 但右键会开 GUI" 的方块
        // 连不上. 现在放宽: 任何有 BE 的方块都能连, 打开走 useWithoutItem 路径.
        int slotCount = 0;
        var handler = Capabilities.ItemHandler.BLOCK.getCapability(
            level, pos, be.getBlockState(), be, null);
        if (handler != null) {
            slotCount = handler.getSlots();
        }
        boolean hasMenuProvider = be.getBlockState().getMenuProvider(level, pos) != null;
        // 不再要求必须有 ItemHandler/MenuProvider: 兜底连接所有有 BE 的方块
        // (c.f. 之前 if (slotCount <= 0 && !hasMenuProvider) return PASS 的早期返回)

        // 注: 不再要求必须有 MenuProvider. 特殊方块 (Mekanism 发电机, 流水线机器等)
        // 也允许绑定, 服务端在打开时会模拟右键触发其自定义 GUI, 客户端 Mixin
        // (ClientPacketListenerMixin) 会绕过 1.21.1 的 "Client could not locate tile"
        // assert, 让任何方块都能被远程打开.

        PersonalWarehouseData data = WarehouseDataManager.get(sp);

        // 等级上限: 当前等级最多可连接 data.getMaxLinkedContainers() 个世界容器
        if (data.getLinkedContainers().size() >= data.getMaxLinkedContainers()) {
            sp.sendSystemMessage(Component.literal(
                "§c[云存储] 已达等级上限 (当前 Lv." + data.getLevel() + " 最多 "
                    + data.getMaxLinkedContainers() + " 个). 请升级解锁更多. §e按 V → 升级"));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // 防重复: 同一个 (维度, 位置) 只能有一条连接
        LinkedContainer existing = data.findLinkByLocation(sp.level().dimension(), pos);
        if (existing != null) {
            if (sp.isShiftKeyDown()) {
                // 潜行 → 断开
                data.removeLink(existing.linkId());
                // 摘掉 PERSISTENT chunk ticket, 让该 chunk 在玩家离开后能正常卸载
                LinkedChunkLoader.remove(existing.linkId());
                sp.sendSystemMessage(Component.literal(
                    "§a[云存储] 已断开对 §f" + existing.name() + " §a的连接"));
                WarehouseDataManager.setDirty();
                WarehouseDataManager.sendSyncLinkedContainers(sp, data);
                return InteractionResult.SUCCESS;
            }
            sp.sendSystemMessage(Component.literal(
                "§e[云存储] 这个位置已经连接过了 (\"" + existing.name() + "\"). 潜行 + 右键可断开."));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // 新连接
        UUID linkId = UUID.randomUUID();
        String blockId = be.getBlockState().getBlock().builtInRegistryHolder().key().location().toString();
        String defaultName = makeDefaultName(data, be.getBlockState().getBlock().getName().getString());
        LinkedContainer link = new LinkedContainer(
            linkId, defaultName, sp.level().dimension(), pos, slotCount, blockId);
        data.addLink(link);
        WarehouseDataManager.setDirty();

        // 挂 PERSISTENT chunk ticket: 让容器所在 chunk 一直保持加载, 熔炉/机器照常 tick
        // 玩家走到天涯海角/去其他维度, 都不影响这个 chunk 工作
        LinkedChunkLoader.add(linkId, sp.level().dimension(), pos);

        // 没有 Item.asItem() 的方块会返回 AIR, fallback 到 BARRIER 当图标
        Item blockItem = be.getBlockState().getBlock().asItem();
        ItemStack iconStack = new ItemStack(blockItem != null && blockItem != net.minecraft.world.item.Items.AIR
            ? blockItem : net.minecraft.world.item.Items.BARRIER);
        WarehouseNetworking.sendTo(sp, new S2COpenNameEntry(linkId, defaultName, iconStack, slotCount));
        WarehouseDataManager.sendSyncLinkedContainers(sp, data);

        ExampleMod.LOGGER.info("[CloudStorage] Player {} linked container at {} ({} slots, dim={})",
            sp.getName().getString(), pos, slotCount, sp.level().dimension().location());

        return InteractionResult.SUCCESS;
    }

    private static String makeDefaultName(PersonalWarehouseData data, String blockName) {
        int n = 1;
        for (var c : data.getLinkedContainers()) {
            if (c.name().startsWith(blockName + " #")) n++;
        }
        return blockName + " #" + n;
    }
}

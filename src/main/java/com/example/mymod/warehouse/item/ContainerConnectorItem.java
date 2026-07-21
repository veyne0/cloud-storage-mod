package com.example.mymod.warehouse.item;

import com.example.mymod.ExampleMod;
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

        // 检查目标方块是否有 ITEM_HANDLER 能力 (NeoForge 1.21.1: BlockEntity.getCapability 没了,
        // 改用 BlockCapability 的静态方法)
        var handler = Capabilities.ItemHandler.BLOCK.getCapability(
            level, pos, be.getBlockState(), be, null);
        int slotCount = handler != null ? handler.getSlots() : 0;
        if (slotCount <= 0) return InteractionResult.PASS;

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

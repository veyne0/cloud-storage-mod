package com.example.mymod.warehouse.item;

import com.example.mymod.warehouse.network.C2SCaptureEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 实体收容器: 对任意非玩家/非 Boss 的实体右键, 把它收容到云存储空间.
 * <p>
 * 行为:
 * <ul>
 *   <li>右键实体 (客户端): 发 {@link C2SCaptureEntity} 包到服务端处理</li>
 *   <li>服务端: 收到包后做黑名单 + 容量 + NBT 收容</li>
 * </ul>
 * 不消耗. 收容后弹命名框.
 * <p>
 * 配方: 1 末影珍珠 + 1 烈焰棒 + 1 鸡蛋 (shapeless) —— 占位, 用户可改.
 */
public class EntityContainerItem extends Item {

    /**
     * {@link DeferredRegister.Items#register} 的 supplier —— 让 {@code ExampleMod} 可以
     * 在不知道 Item 实际类的情况下, 把它注册到 DeferredItem.
     */
    public static final java.util.function.Supplier<EntityContainerItem> ENTITY_CONTAINER_ITEM =
        () -> new EntityContainerItem(new Item.Properties().stacksTo(1));

    public EntityContainerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7对实体右键即可将其收容到云存储空间,"));
        tooltip.add(Component.literal("§7在仓库界面左键可召唤/召回,"));
        tooltip.add(Component.literal("§7右键可改名或解除收容"));
        tooltip.add(Component.literal("§8容量: 2/4/6/8/10 按仓库等级"));
        super.appendHoverText(stack, ctx, tooltip, flag);
    }

    /**
     * 客户端 + 服务端都走这里. 客户端发包, 服务端其实不会被这个调用 (Minecraft 服务端是
     * 单独的 thread, 没有物品交互); 但保留这个钩子, 防止某些 mod 在服务端主动触发交互.
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                   InteractionHand hand) {
        if (player.level().isClientSide()) {
            PacketDistributor.sendToServer(new C2SCaptureEntity(target.getId()));
        }
        return InteractionResult.CONSUME;
    }
}

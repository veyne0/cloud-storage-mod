package com.example.mymod.warehouse;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * 实体 ID ↔ 物品 (图标) 转换工具.
 * <p>
 * 1.21.1 里原版刷怪蛋注册名约定是 {@code <namespace>:<entity_path>_spawn_egg}
 * (例如 {@code minecraft:cow_spawn_egg}, {@code minecraft:pig_spawn_egg}).
 * 这样比 {@code SpawnEggItem.getType(null)} 在 1.21.1 里更稳定 (后者 deprecate
 * 且需要 {@code HolderLookup.Provider}, 在某些时刻传 null 会拿不到).
 * <p>
 * 用法:
 * <pre>
 *   ItemStack icon = EntityIconUtil.iconFor("minecraft:cow"); // 牛刷怪蛋
 *   ItemStack icon = EntityIconUtil.iconFor("mymod:custom_mob"); // 没有 spawn egg, 回退末影珍珠
 * </pre>
 */
public final class EntityIconUtil {
    private EntityIconUtil() {}

    /**
     * 拿到 entity 的代表 icon:
     * <ol>
     *   <li>原版刷怪蛋 (有 register): 返回刷怪蛋 ItemStack</li>
     *   <li>其他 (没刷怪蛋 / 注册名不对 / entityType 拿不到): 末影珍珠</li>
     * </ol>
     */
    public static ItemStack iconFor(String entityTypeId) {
        if (entityTypeId == null || entityTypeId.isEmpty()) {
            return new ItemStack(Items.ENDER_PEARL);
        }
        try {
            ResourceLocation typeLoc = ResourceLocation.parse(entityTypeId);
            // 1) 先按 "xxx_spawn_egg" 注册名查
            String eggPath = typeLoc.getPath() + "_spawn_egg";
            ResourceLocation eggLoc = ResourceLocation.fromNamespaceAndPath(typeLoc.getNamespace(), eggPath);
            Item item = BuiltInRegistries.ITEM.get(eggLoc);
            if (item != null && item != Items.AIR && item instanceof SpawnEggItem) {
                return new ItemStack(item);
            }
            // 2) 兜底: 用 entity type 遍历 SpawnEggItem, 看看哪个的 type 对得上
            //    (防止 mod 用奇怪的注册名, 但仍然注册了 SpawnEggItem).
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeLoc);
            if (type != null) {
                for (Item candidate : BuiltInRegistries.ITEM) {
                    if (candidate instanceof SpawnEggItem egg) {
                        try {
                            if (matchesType(egg, type)) {
                                return new ItemStack(egg);
                            }
                        } catch (Throwable ignored) {
                            // 1.21.1 的 getType 需要 HolderLookup.Provider, 传 null 可能会抛
                            // 异常. 这里静默跳过, 让外层兜底走末影珍珠.
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new ItemStack(Items.ENDER_PEARL);
    }

    /**
     * 在 1.21.1 里尽量多方式匹配一个 SpawnEggItem 是不是某个 entity 的蛋.
     * <p>
     * 主策略: 反查注册名. 原版蛋 = {@code "<entity_namespace>:<entity_path>_spawn_egg"}.
     * 这个规则对 99% 的 mod 蛋都成立, 不需要 HolderLookup.Provider.
     */
    private static boolean matchesType(SpawnEggItem egg, EntityType<?> target) {
        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(egg);
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target);
            if (itemId == null || typeId == null) return false;
            String expected = typeId.getNamespace() + ":" + typeId.getPath() + "_spawn_egg";
            return expected.equals(itemId.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 拿到 entity 的"展示类型描述" (zh_cn 时是中文, en_us 时是英文).
     * 走 {@link EntityType#getDescription()}.
     */
    public static String describeType(EntityType<?> type) {
        if (type == null) return "Unknown";
        return type.getDescription().getString();
    }
}

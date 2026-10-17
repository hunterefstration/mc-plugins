package dev.luckboost;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Rough "how good is this loot" score, used to pick the best of several loot rolls. */
final class LootValue {

    private static final Map<Material, Double> VALUES = new EnumMap<>(Material.class);

    static {
        put(150, "NETHER_STAR", "ELYTRA", "HEAVY_CORE", "MACE", "TRIDENT", "HEART_OF_THE_SEA", "DRAGON_EGG",
                "NETHERITE_UPGRADE_SMITHING_TEMPLATE", "SNIFFER_EGG");
        put(120, "ENCHANTED_GOLDEN_APPLE", "NETHERITE_BLOCK");
        put(100, "NETHERITE_INGOT", "NETHERITE_SWORD", "NETHERITE_PICKAXE", "NETHERITE_AXE", "NETHERITE_SHOVEL",
                "NETHERITE_HOE", "NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS");
        put(80, "TOTEM_OF_UNDYING", "BEACON", "SHULKER_SHELL");
        put(270, "DIAMOND_BLOCK");
        put(60, "ANCIENT_DEBRIS", "NETHERITE_SCRAP", "OMINOUS_TRIAL_KEY", "WIND_CHARGE_POTTERY_SHERD");
        put(40, "DIAMOND_SWORD", "DIAMOND_PICKAXE", "DIAMOND_AXE", "DIAMOND_SHOVEL", "DIAMOND_HOE", "DIAMOND_HELMET",
                "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS", "DIAMOND_HORSE_ARMOR", "MUSIC_DISC_PIGSTEP",
                "MUSIC_DISC_OTHERSIDE", "MUSIC_DISC_CREATOR", "MUSIC_DISC_PRECIPICE", "MUSIC_DISC_RELIC");
        put(30, "DIAMOND", "GOLDEN_APPLE", "SADDLE", "NAME_TAG", "OMINOUS_BOTTLE", "EXPERIENCE_BOTTLE",
                "GOLDEN_CARROT", "GUSTER_BANNER_PATTERN", "FLOW_BANNER_PATTERN", "PIGLIN_BANNER_PATTERN");
        put(108, "EMERALD_BLOCK");
        put(54, "GOLD_BLOCK");
        put(27, "IRON_BLOCK");
        put(12, "EMERALD", "TRIAL_KEY", "ENDER_PEARL", "BLAZE_ROD", "GHAST_TEAR", "NAUTILUS_SHELL",
                "GOLDEN_HORSE_ARMOR", "IRON_HORSE_ARMOR", "CROSSBOW", "BOW", "SHIELD");
        put(8, "GOLD_INGOT", "LAPIS_BLOCK", "OBSIDIAN", "CRYING_OBSIDIAN", "QUARTZ_BLOCK", "AMETHYST_SHARD",
                "IRON_SWORD", "IRON_PICKAXE", "IRON_AXE", "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
                "FIRE_CHARGE", "SPECTRAL_ARROW");
        put(4, "IRON_INGOT", "COPPER_INGOT", "REDSTONE_BLOCK", "COAL_BLOCK", "GLOWSTONE", "PRISMARINE_SHARD",
                "PRISMARINE_CRYSTALS", "MAGMA_CREAM", "SLIME_BALL", "SALMON", "COD", "TROPICAL_FISH", "PUFFERFISH");
        put(2, "GOLD_NUGGET", "IRON_NUGGET", "LAPIS_LAZULI", "REDSTONE", "QUARTZ", "COAL", "RAW_IRON", "RAW_GOLD",
                "RAW_COPPER", "ARROW", "BREAD", "APPLE");
        put(0.25, "ROTTEN_FLESH", "STRING", "BONE", "STICK", "WHEAT_SEEDS", "LEATHER", "GUNPOWDER", "SPIDER_EYE",
                "DIRT", "GRAVEL", "COBBLESTONE", "COBBLED_DEEPSLATE", "NETHERRACK", "SAND", "BOWL", "INK_SAC",
                "LILY_PAD", "TRIPWIRE_HOOK", "GLASS_BOTTLE", "POISONOUS_POTATO", "KELP");
    }

    private static void put(double value, String... names) {
        for (String name : names) {
            Material m = Material.matchMaterial(name);
            if (m != null) VALUES.put(m, value);
        }
    }

    private LootValue() {}

    static double of(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        Material type = item.getType();
        double each = VALUES.getOrDefault(type, defaultValue(type));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) each += enchantValue(e.getKey(), e.getValue());
            if (meta instanceof EnchantmentStorageMeta book) {
                each += 10;
                for (Map.Entry<Enchantment, Integer> e : book.getStoredEnchants().entrySet()) {
                    each += enchantValue(e.getKey(), e.getValue()) * 1.5;
                }
            }
        }
        // Big stacks of cheap things shouldn't beat one great item.
        return each * Math.min(item.getAmount(), 16) + (item.getAmount() > 16 ? each * Math.sqrt(item.getAmount() - 16) : 0);
    }

    private static double enchantValue(Enchantment enchantment, int level) {
        double v = 6.0 * level / Math.max(1, enchantment.getMaxLevel()) * Math.max(1, enchantment.getMaxLevel() / 2.0 + 0.5);
        if (enchantment.equals(Enchantment.MENDING) || enchantment.isTreasure()) v += 25;
        if (enchantment.isCursed()) v = -10;
        return v;
    }

    private static double defaultValue(Material type) {
        String n = type.name();
        if (n.startsWith("MUSIC_DISC")) return 25;
        if (n.endsWith("_SMITHING_TEMPLATE")) return 35;
        if (n.endsWith("_POTTERY_SHERD")) return 10;
        if (n.contains("POTION") || n.endsWith("TIPPED_ARROW")) return 6;
        if (n.startsWith("GOLDEN_")) return 6;
        if (n.startsWith("CHAINMAIL_")) return 8;
        return 1;
    }

    static double of(Collection<ItemStack> items) {
        double total = 0;
        for (ItemStack i : items) total += of(i);
        return total;
    }
}

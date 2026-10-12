package dev.effectbooks.gui;

import dev.effectbooks.util.Effects;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Icons {

    private Icons() {}

    static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, Arrays.asList(lore));
    }

    static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(name);
        List<String> lines = new ArrayList<>();
        for (String l : lore) lines.add(ChatColor.GRAY + l);
        meta.setLore(lines);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * An icon for an effect: a themed item (sugar for Speed, rabbit foot for Jump Boost...),
     * or a potion in the effect's colour if there's no themed item for it.
     */
    static ItemStack effect(PotionEffectType type, String name, String... lore) {
        Material material = Effects.iconMaterial(type);
        if (material != null) return icon(material, name, lore);

        ItemStack item = icon(Material.POTION, name, lore);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setColor(type.getColor());
        // Potions ignore item_name (they show "Uncraftable Potion"), so use a custom name instead.
        meta.setDisplayName(ChatColor.RESET + name);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack filler() {
        return icon(Material.BLACK_STAINED_GLASS_PANE, " ");
    }
}

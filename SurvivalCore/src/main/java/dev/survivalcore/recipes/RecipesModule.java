package dev.survivalcore.recipes;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * Extra crafting recipes:
 * <ul>
 *   <li>Cobweb: 5 string in an X (or a plus)</li>
 *   <li>Cheap golden apple: an apple with 4 gold ingots around it (plus shape)</li>
 *   <li>Enchanted golden apple: an apple surrounded by 8 gold blocks (old recipe, off by default)</li>
 * </ul>
 * The vanilla recipes still work too.
 */
public final class RecipesModule extends Module {

    private final List<NamespacedKey> added = new ArrayList<>();

    public RecipesModule(SurvivalCore plugin) {
        super(plugin, "recipes", "Recipes");
    }

    @Override
    public void enable() {
        if (config().getBoolean("cobweb.enabled", true)) {
            int amount = Math.max(1, Math.min(64, config().getInt("cobweb.amount", 1)));
            add(shaped("cobweb_x", new ItemStack(Material.COBWEB, amount), "S S", " S ", "S S")
                    .setIngredient('S', Material.STRING));
            add(shaped("cobweb_plus", new ItemStack(Material.COBWEB, amount), " S ", "SSS", " S ")
                    .setIngredient('S', Material.STRING));
        }
        if (config().getBoolean("cheap-golden-apple.enabled", true)) {
            add(shaped("cheap_golden_apple", new ItemStack(Material.GOLDEN_APPLE), " G ", "GAG", " G ")
                    .setIngredient('G', Material.GOLD_INGOT)
                    .setIngredient('A', Material.APPLE));
        }
        if (config().getBoolean("enchanted-golden-apple.enabled", false)) {
            add(shaped("enchanted_golden_apple", new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), "GGG", "GAG", "GGG")
                    .setIngredient('G', Material.GOLD_BLOCK)
                    .setIngredient('A', Material.APPLE));
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(added);
    }

    @Override
    public void disable() {
        for (NamespacedKey key : added) Bukkit.removeRecipe(key);
        added.clear();
    }

    private ShapedRecipe shaped(String id, ItemStack result, String... shape) {
        return new ShapedRecipe(new NamespacedKey(plugin, id), result).shape(shape);
    }

    private void add(ShapedRecipe recipe) {
        Bukkit.removeRecipe(recipe.getKey());
        if (Bukkit.addRecipe(recipe)) added.add(recipe.getKey());
    }

    /** Show the recipes in everyone's recipe book. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(added);
    }
}

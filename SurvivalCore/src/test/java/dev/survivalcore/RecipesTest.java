package dev.survivalcore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapedRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

class RecipesTest {

    private SurvivalCore plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(SurvivalCore.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void recipesAreRegistered() {
        ShapedRecipe web = (ShapedRecipe) Bukkit.getRecipe(new NamespacedKey(plugin, "cobweb_x"));
        assertNotNull(web);
        assertEquals(Material.COBWEB, web.getResult().getType());
        assertNotNull(Bukkit.getRecipe(new NamespacedKey(plugin, "cobweb_plus")));
        ShapedRecipe gap = (ShapedRecipe) Bukkit.getRecipe(new NamespacedKey(plugin, "cheap_golden_apple"));
        assertNotNull(gap);
        assertEquals(Material.GOLDEN_APPLE, gap.getResult().getType());
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "enchanted_golden_apple")), "off by default");
    }

    @Test
    void turningOffRemovesThem() {
        plugin.getConfig().set("recipes.enabled", false);
        plugin.saveConfig();
        plugin.reloadAll();
        assertNull(Bukkit.getRecipe(new NamespacedKey(plugin, "cobweb_x")));
        assertFalse(plugin.isEnabled(plugin.module("recipes")));
    }
}

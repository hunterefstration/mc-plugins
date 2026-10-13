package dev.survivalcore.luck;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LuckTest {

    private ServerMock server;
    private SurvivalCore plugin;
    private LuckModule module;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(SurvivalCore.class);
        module = (LuckModule) plugin.module("luck-boost");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void betterLootScoresHigher() {
        assertTrue(LootValue.of(new ItemStack(Material.DIAMOND)) > LootValue.of(new ItemStack(Material.IRON_INGOT)));
        assertTrue(LootValue.of(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE))
                > LootValue.of(new ItemStack(Material.ROTTEN_FLESH, 64)), "one god apple beats a stack of junk");
        ItemStack plain = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemStack enchanted = new ItemStack(Material.DIAMOND_PICKAXE);
        enchanted.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
        assertTrue(LootValue.of(enchanted) > LootValue.of(plain));
        assertTrue(LootValue.of(List.of(new ItemStack(Material.EMERALD), new ItemStack(Material.DIAMOND)))
                > LootValue.of(List.of(new ItemStack(Material.DIAMOND))));
    }

    @Test
    void opsSetLevelsSilently() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        PlayerMock target = server.addPlayer("Lucky");
        assertTrue(op.performCommand("luckboost Lucky 5"));
        assertEquals(5, module.level(target));
        if (target.getAttribute(Attribute.LUCK) != null) { // MockBukkit players may not have it
            assertEquals(5.0, target.getAttribute(Attribute.LUCK).getValue(), 0.001);
        }
        assertNull(target.nextMessage(), "the player is never told");

        op.performCommand("luckboost Lucky off");
        assertEquals(0, module.level(target));
        if (target.getAttribute(Attribute.LUCK) != null) {
            assertEquals(0.0, target.getAttribute(Attribute.LUCK).getValue(), 0.001);
        }
    }

    @Test
    void notListedAndPlayersCantUseIt() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        op.performCommand("sc");
        String line;
        while ((line = op.nextMessage()) != null) assertFalse(line.contains("Luck"), line);

        PlayerMock normal = server.addPlayer("Normal");
        normal.performCommand("luckboost Normal 50");
        assertEquals(0, module.level(normal));
    }

    @Test
    void levelSurvivesReload() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        PlayerMock target = server.addPlayer("Lucky");
        op.performCommand("luckboost Lucky 3");
        plugin.reloadAll();
        assertEquals(3, ((LuckModule) plugin.module("luck-boost")).level(target));
    }
}

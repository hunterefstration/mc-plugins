package dev.survivalcore.enderchest;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

class EnderChestTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.load(SurvivalCore.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void firstThreeRowsAreTheVanillaChest() {
        PlayerMock steve = server.addPlayer("Steve");
        steve.setOp(true);
        steve.getEnderChest().setItem(4, new ItemStack(Material.DIAMOND, 3));

        assertTrue(steve.performCommand("enderchest"));
        Inventory big = steve.getOpenInventory().getTopInventory();
        assertEquals(54, big.getSize());
        assertEquals(Material.DIAMOND, big.getItem(4).getType(), "vanilla contents show up");

        big.setItem(4, null);
        big.setItem(10, new ItemStack(Material.EMERALD));
        steve.closeInventory();
        assertNull(steve.getEnderChest().getItem(4));
        assertEquals(Material.EMERALD, steve.getEnderChest().getItem(10).getType(), "written back to vanilla");
    }

    @Test
    void sharedBetweenViewers() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock admin = server.addPlayer("Admin");
        steve.setOp(true);
        admin.setOp(true);
        steve.performCommand("enderchest");
        admin.performCommand("enderchest Steve");
        assertSame(steve.getOpenInventory().getTopInventory(), admin.getOpenInventory().getTopInventory());
    }
}

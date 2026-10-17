package dev.survivalcore.villagers;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/** Villagers picked up with the old ClickVillagers plugin. */
class ClickVillagersItemTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        MockBukkit.load(SurvivalCore.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Built exactly like ClickVillagers 1.6.3's PickupManager.writeData. */
    private static ItemStack oldItem(String nbt) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey("clickvillagers", "villager"), PersistentDataType.BOOLEAN, true);
        pdc.set(new NamespacedKey("clickvillagers", "type"), PersistentDataType.STRING, "VILLAGER");
        pdc.set(new NamespacedKey("clickvillagers", "nbt"), PersistentDataType.STRING, nbt);
        pdc.set(new NamespacedKey("clickvillagers", "data_version"), PersistentDataType.INTEGER, 4671);
        item.setItemMeta(meta);
        return item;
    }

    @Test
    void recognisesOldItemsOnly() {
        assertTrue(VillagerPickupModule.isClickVillagersItem(oldItem("{id:\"minecraft:villager\"}")));
        assertFalse(VillagerPickupModule.isClickVillagersItem(new ItemStack(Material.PLAYER_HEAD)));
        assertFalse(VillagerPickupModule.isClickVillagersItem(null));
    }

    @Test
    void addsTheIdLikeClickVillagersDid() {
        assertEquals("{Health:20.0f,id:\"minecraft:villager\"}",
                VillagerPickupModule.withId("{Health:20.0f}", EntityType.VILLAGER));
        assertEquals("{id:\"minecraft:villager\"}", VillagerPickupModule.withId("{}", EntityType.VILLAGER));
        assertEquals("{id:\"minecraft:zombie_villager\"}",
                VillagerPickupModule.withId("{id:\"minecraft:zombie_villager\"}", EntityType.ZOMBIE_VILLAGER));
    }

    @Test
    void fixesOldEnchantmentLevels() {
        assertEquals("{enchantments:{\"minecraft:mending\":1}}",
                VillagerPickupModule.fixLevels("{enchantments:{levels:{\"minecraft:mending\":1}}}"));
    }

    @Test
    void placingAnOldItemNeverPlacesAPlainHead() {
        PlayerMock steve = server.addPlayer("Steve");
        ItemStack item = oldItem("{id:\"minecraft:villager\"}");
        steve.getInventory().setItemInMainHand(item);
        Block at = world.getBlockAt(0, 65, 0);
        Block against = world.getBlockAt(0, 64, 0);
        against.setType(Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(at, at.getState(), against, item, steve, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "the head must not be placed (that would lose the villager)");
    }
}

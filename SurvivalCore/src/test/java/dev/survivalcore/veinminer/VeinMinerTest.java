package dev.survivalcore.veinminer;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VeinMinerTest {

    private VeinMinerModule module;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        SurvivalCore core = MockBukkit.load(SurvivalCore.class);
        module = (VeinMinerModule) core.module("vein-miner");
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void deepslateAndNormalOresAreOneVein() {
        assertEquals(module.groupOf(Material.IRON_ORE), module.groupOf(Material.DEEPSLATE_IRON_ORE));
        assertNotEquals(module.groupOf(Material.IRON_ORE), module.groupOf(Material.GOLD_ORE));
        assertNotNull(module.groupOf(Material.ANCIENT_DEBRIS));
        assertNull(module.groupOf(Material.STONE));
    }

    @Test
    void logsOnlyMatchTheirOwnWood() {
        assertNotNull(module.groupOf(Material.OAK_LOG));
        assertNotNull(module.groupOf(Material.CRIMSON_STEM));
        assertNotEquals(module.groupOf(Material.OAK_LOG), module.groupOf(Material.BIRCH_LOG));
    }

    @Test
    void findsConnectedVeinIncludingDiagonals() {
        Block origin = world.getBlockAt(0, 10, 0);
        origin.setType(Material.DIAMOND_ORE);
        world.getBlockAt(1, 10, 0).setType(Material.DEEPSLATE_DIAMOND_ORE);
        world.getBlockAt(2, 11, 1).setType(Material.DIAMOND_ORE); // diagonal to the previous one
        world.getBlockAt(5, 10, 0).setType(Material.DIAMOND_ORE); // not connected
        world.getBlockAt(0, 11, 0).setType(Material.COAL_ORE);    // different ore

        List<Block> vein = module.findVein(origin, module.groupOf(Material.DIAMOND_ORE), 64);
        assertEquals(2, vein.size());
        assertEquals(world.getBlockAt(1, 10, 0), vein.get(0), "nearest first");
        assertFalse(vein.contains(origin));
    }

    @Test
    void treeIsCappedAtMax() {
        for (int y = 0; y < 20; y++) world.getBlockAt(0, 70 + y, 0).setType(Material.OAK_LOG);
        List<Block> tree = module.findVein(world.getBlockAt(0, 70, 0), module.groupOf(Material.OAK_LOG), 5);
        assertEquals(5, tree.size());
    }
}

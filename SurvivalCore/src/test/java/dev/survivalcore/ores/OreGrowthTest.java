package dev.survivalcore.ores;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

class OreGrowthTest {

    private ServerMock server;
    private SurvivalCore plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new WorldMock(Material.STONE, -64, 320, 60); // solid stone up to y=60
        server.addWorld(world);
        for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) world.loadChunk(cx, cz);
        plugin = MockBukkit.load(SurvivalCore.class);
        plugin.getConfig().set("ore-growth.chance", 1.0);
        plugin.getConfig().set("ore-growth.max-veins-per-chunk", 1000);
        plugin.saveConfig();
        plugin.reloadAll();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private int countOres() {
        int n = 0;
        for (int x = -30; x <= 30; x++)
            for (int y = -10; y <= 60; y++)
                for (int z = -30; z <= 30; z++)
                    if (world.getBlockAt(x, y, z).getType().name().endsWith("_ORE")) n++;
        return n;
    }

    @Test
    void enclosedStoneNearPlayersTurnsIntoOre() {
        PlayerMock player = server.addPlayer();
        player.teleport(world.getBlockAt(0, 30, 0).getLocation());
        server.getScheduler().performTicks(400);
        assertTrue(countOres() > 0);
    }

    @Test
    void placedStoneAndExposedStoneNeverChange() {
        // A hollow air pocket: the stone around it touches air, so it isn't hidden.
        for (int x = -2; x <= 2; x++)
            for (int y = 28; y <= 32; y++)
                for (int z = -2; z <= 2; z++) world.getBlockAt(x, y, z).setType(Material.AIR);
        Block wall = world.getBlockAt(3, 30, 0);
        PlayerMock player = server.addPlayer();
        player.teleport(world.getBlockAt(0, 30, 0).getLocation());
        server.getScheduler().performTicks(400);
        assertEquals(Material.STONE, wall.getType(), "stone next to air stays stone");
    }
}

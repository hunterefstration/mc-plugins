package dev.survivalcore.endportal;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.EndPortalFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndPortalTest {

    private WorldMock world;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Builds the 12 frames around a 3x3 portal centred on (cx, y, cz); returns them. */
    private List<Block> ring(int cx, int y, int cz, int eyes) {
        List<Block> frames = new ArrayList<>();
        for (int i = -1; i <= 1; i++) {
            frames.add(frame(cx + i, y, cz - 2, BlockFace.SOUTH));
            frames.add(frame(cx + i, y, cz + 2, BlockFace.NORTH));
            frames.add(frame(cx - 2, y, cz + i, BlockFace.EAST));
            frames.add(frame(cx + 2, y, cz + i, BlockFace.WEST));
        }
        for (int i = 0; i < eyes; i++) setEye(frames.get(i), true);
        return frames;
    }

    private Block frame(int x, int y, int z, BlockFace facing) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(Material.END_PORTAL_FRAME);
        EndPortalFrame data = (EndPortalFrame) b.getBlockData();
        data.setFacing(facing);
        data.setEye(false);
        b.setBlockData(data);
        return b;
    }

    private static void setEye(Block b, boolean eye) {
        EndPortalFrame data = (EndPortalFrame) b.getBlockData();
        data.setEye(eye);
        b.setBlockData(data);
    }

    @Test
    void twelfthEyeIsDetectedFromEveryFrame() {
        List<Block> frames = ring(100, 30, 100, 0);
        for (Block last : frames) {
            for (Block f : frames) setEye(f, f != last);
            assertTrue(EndPortalModule.wouldComplete(last), "frame " + last.getLocation());
        }
    }

    @Test
    void earlierEyesAreFine() {
        List<Block> frames = ring(0, 30, 0, 10);
        assertFalse(EndPortalModule.wouldComplete(frames.get(10)), "11th eye");
    }

    @Test
    void ringWithAFrameFacingOutwardsNeverLights() {
        List<Block> frames = ring(50, 30, 50, 11);
        Block wrong = frames.get(3);
        EndPortalFrame data = (EndPortalFrame) wrong.getBlockData();
        data.setFacing(data.getFacing().getOppositeFace());
        wrong.setBlockData(data);
        assertFalse(EndPortalModule.wouldComplete(frames.get(11)));
    }

    @Test
    void absurdTimersAreRejected() {
        assertNull(EndPortalModule.parseDuration("99999999999999999999s"));
        assertNull(EndPortalModule.parseDuration("999999999999d"));
        assertNull(EndPortalModule.parseDuration("4000d"));
    }

    @Test
    void parsesAndFormatsDurations() {
        assertEquals(Duration.ofMinutes(150), EndPortalModule.parseDuration("2h30m"));
        assertEquals(Duration.ofDays(1), EndPortalModule.parseDuration("1d"));
        assertNull(EndPortalModule.parseDuration("soon"));
        assertEquals("2h 30m", EndPortalModule.format(Duration.ofMinutes(150)));
    }
}

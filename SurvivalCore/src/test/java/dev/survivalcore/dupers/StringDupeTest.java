package dev.survivalcore.dupers;

import dev.survivalcore.SurvivalCore;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.block.data.type.TripwireHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 1.21.1 string dupe rules, checked against a tiny fake world (MockBukkit has no tripwire
 * block data). A line runs west to east: hook at x=0 facing east, string at x=1..4, hook at x=5
 * facing west.
 */
class StringDupeTest {

    private DupersModule module;
    private final Map<List<Integer>, Object[]> world = new HashMap<>();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        SurvivalCore core = MockBukkit.load(SurvivalCore.class);
        module = (DupersModule) core.module("dupers");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------ fake world

    private static final class WireState {
        boolean disarmed, powered, attached;
    }

    private Tripwire wire(WireState st) {
        return (Tripwire) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Tripwire.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "isDisarmed" -> st.disarmed;
                    case "isPowered" -> st.powered;
                    case "isAttached" -> st.attached;
                    case "setDisarmed" -> { st.disarmed = (boolean) args[0]; yield null; }
                    case "setPowered" -> { st.powered = (boolean) args[0]; yield null; }
                    case "setAttached" -> { st.attached = (boolean) args[0]; yield null; }
                    case "clone" -> {
                        WireState copy = new WireState();
                        copy.disarmed = st.disarmed;
                        copy.powered = st.powered;
                        copy.attached = st.attached;
                        yield wire(copy);
                    }
                    case "getMaterial" -> Material.TRIPWIRE;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private TripwireHook hook(BlockFace facing, boolean attached) {
        return (TripwireHook) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TripwireHook.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getFacing" -> facing;
                    case "isAttached" -> attached;
                    case "getMaterial" -> Material.TRIPWIRE_HOOK;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Block block(int x, int y, int z) {
        List<Integer> pos = List.of(x, y, z);
        return (Block) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Block.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getType" -> world.containsKey(pos) ? world.get(pos)[0] : Material.AIR;
                    case "getBlockData" -> world.containsKey(pos) ? world.get(pos)[1] : null;
                    case "getRelative" -> {
                        BlockFace f = (BlockFace) args[0];
                        int n = args.length > 1 ? (int) args[1] : 1;
                        yield block(x + f.getModX() * n, y + f.getModY() * n, z + f.getModZ() * n);
                    }
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "hashCode" -> pos.hashCode();
                    case "equals" -> args[0] instanceof Block b && b.getX() == x && b.getY() == y && b.getZ() == z;
                    case "toString" -> "Block" + pos;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private WireState[] buildLine(boolean hooksAttached, boolean disarmed) {
        world.put(List.of(0, 64, 0), new Object[]{Material.TRIPWIRE_HOOK, hook(BlockFace.EAST, hooksAttached)});
        world.put(List.of(5, 64, 0), new Object[]{Material.TRIPWIRE_HOOK, hook(BlockFace.WEST, hooksAttached)});
        WireState[] states = new WireState[6];
        for (int x = 1; x <= 4; x++) {
            WireState st = new WireState();
            st.attached = hooksAttached;
            st.disarmed = disarmed;
            states[x] = st;
            world.put(List.of(x, 64, 0), new Object[]{Material.TRIPWIRE, wire(st)});
        }
        return states;
    }

    // ------------------------------------------------------------------ rules

    @Test
    void disarmedStringInAttachedLineIsPutBack() {
        buildLine(true, true);
        Block target = block(2, 64, 0);
        module.removed(target, false, new DupersModule.Removal(false));
        assertEquals(List.of(target), module.pendingBlocks());
        Tripwire placed = (Tripwire) module.pendingState(target);
        assertTrue(placed.isDisarmed());
        assertFalse(placed.isAttached(), "hooks detach, so the string comes back unattached");
        assertTrue(placed.isPowered(), "1.21.1 put it back with powered=true");
    }

    @Test
    void armedStringJustBreaks() {
        buildLine(true, false);
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void breakingWithShearsDisarmsItFirst() {
        buildLine(true, false);
        module.removed(block(3, 64, 0), true, new DupersModule.Removal(false));
        assertEquals(1, module.pendingBlocks().size());
    }

    @Test
    void detachedLineDoesNothing() {
        buildLine(false, true);
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void lineWithoutSecondHookDoesNothing() {
        buildLine(true, true);
        world.remove(List.of(5, 64, 0));
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void wrongWayHookDoesNothing() {
        buildLine(true, true);
        world.put(List.of(0, 64, 0), new Object[]{Material.TRIPWIRE_HOOK, hook(BlockFace.WEST, true)});
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void oneExplosionOnlyDupesOneStringPerLine() {
        // Vanilla removes exploded blocks one by one: the first string detaches the hooks.
        buildLine(true, true);
        module.explosion(List.of(block(1, 64, 0), block(2, 64, 0)));
        assertEquals(1, module.pendingBlocks().size());
    }

    @Test
    void armedStringDupesWhenTheLineHasAGap() {
        // 1.21.1: after one armed string is broken the hooks stay attached for up to 10 ticks;
        // breaking a second one in that time puts the second back. (The hook is found by looking
        // south/west through unbroken string, so the gap is on the east side here.)
        buildLine(true, false);
        world.remove(List.of(4, 64, 0)); // the first string is already gone
        Block second = block(2, 64, 0);
        module.removed(second, false, new DupersModule.Removal(false));
        assertEquals(List.of(second), module.pendingBlocks());
        assertFalse(((Tripwire) module.pendingState(second)).isAttached());
    }

    @Test
    void gapOnTheSearchSideMeansNoHookIsFound() {
        buildLine(true, false);
        world.remove(List.of(1, 64, 0));
        module.removed(block(3, 64, 0), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void explosionTakingTheHookFirstDoesNotDupe() {
        buildLine(true, true);
        module.explosion(List.of(block(0, 64, 0), block(2, 64, 0)));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void explosionTakingTheHooksWallFirstDoesNotDupe() {
        buildLine(true, true);
        // The west hook faces east, so it hangs on the block west of it.
        module.explosion(List.of(block(-1, 64, 0), block(2, 64, 0)));
        assertTrue(module.pendingBlocks().isEmpty());
    }

    @Test
    void sameStringIsOnlyQueuedOnce() {
        buildLine(true, true);
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        module.removed(block(2, 64, 0), false, new DupersModule.Removal(false));
        assertEquals(1, module.pendingBlocks().size());
    }

    @Test
    void nonStringBlocksAreIgnored() {
        buildLine(true, true);
        module.removed(block(0, 64, 0), false, new DupersModule.Removal(false));
        module.removed(block(9, 64, 9), false, new DupersModule.Removal(false));
        assertTrue(module.pendingBlocks().isEmpty());
    }
}

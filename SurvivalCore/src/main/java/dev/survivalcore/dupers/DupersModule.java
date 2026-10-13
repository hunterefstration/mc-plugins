package dev.survivalcore.dupers;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.PaperSettings;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.block.data.type.TripwireHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Brings back the dupers Paper and Mojang removed.
 *
 * <ul>
 *   <li><b>Tripwire hook dupers</b>: Paper blocks these with a validation check; this module turns
 *   Paper's own {@code skip-tripwire-hook-placement-validation} switch on, so the real vanilla
 *   code runs again.</li>
 *   <li><b>String dupers</b>: Mojang removed these in 1.21.2, so there is no switch for them. This
 *   module re-creates the exact 1.21.1 behaviour: when a string is destroyed (by flowing water,
 *   an explosion, a player, the Wither...) and that makes its hook-to-hook line change state (a
 *   disarmed string, or a gap left by another string), the hooks
 *   detach and put the string straight back, so the string drops as an item <i>and</i> stays.
 *   Old-style automatic string farms work unchanged.</li>
 * </ul>
 */
public final class DupersModule extends Module {

    /** Vanilla tripwire hooks look at most 41 blocks along the string. */
    private static final int MAX_LINE = 42;

    private PaperSettings paper;
    private boolean stringDupe;
    private boolean shearDupe;

    /** Strings to put back at the end of this tick (vanilla did it during the same block update). */
    private final List<Pending> pending = new ArrayList<>();

    private record Pending(Block block, BlockData state, boolean explosion) {}

    public DupersModule(SurvivalCore plugin) {
        super(plugin, "dupers", "Dupers");
    }

    @Override
    public void enable() {
        pending.clear();
        stringDupe = config().getBoolean("string-dupe", true);
        shearDupe = config().getBoolean("string-dupe-with-shears", true);
        paper = new PaperSettings(plugin, displayName());
        if (config().getBoolean("tripwire-hook-dupe", true)) {
            paper.want("unsupportedSettings.skipTripwireHookPlacementValidation", true);
        }
        if (config().getBoolean("tnt-carpet-rail-dupe", false)) {
            paper.want("unsupportedSettings.allowPistonDuplication", true);
        }
        if (config().getBoolean("sand-gravel-dupe", false)) {
            paper.want("unsupportedSettings.allowUnsafeEndPortalTeleportation", true);
        }
        paper.apply();
    }

    @Override
    public void disable() {
        if (paper != null) paper.restore();
        pending.clear();
    }

    // ------------------------------------------------------------------ ways a string gets destroyed

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        removed(event.getToBlock(), false, new Removal(false));
    }

    // Pistons and buckets aren't listed: in 1.21.1 the moving block / placed water immediately
    // overwrote the string the hooks put back, so they never duped. The tick-end check below also
    // refuses to overwrite anything but air or flowing liquid, for the same reason.
    // (/setblock, /fill and other plugins breaking blocks can't be seen by plugins at all.)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // Vanilla marks string as disarmed when it's broken with shears, just before removing it.
        boolean shears = event.getPlayer().getInventory().getItemInMainHand().getType() == Material.SHEARS;
        removed(event.getBlock(), shears && shearDupe, new Removal(false));
    }

    /** The Wither (and other mobs) breaking blocks. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.TRIPWIRE && event.getTo().isAir()) {
            removed(event.getBlock(), false, new Removal(false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explosion(event.blockList());
    }

    /**
     * Vanilla removes exploded blocks one at a time, in this order. A hook (or the block a hook
     * hangs on) going first detaches its line before later strings go; strings that already went
     * are gaps in the line for the ones after them.
     */
    void explosion(List<Block> blocks) {
        Removal removal = new Removal(true);
        for (Block b : blocks) {
            if (b.getType() == Material.TRIPWIRE) {
                removed(b, false, removal);
            } else {
                if (b.getType() == Material.TRIPWIRE_HOOK) detachLine(b, removal);
                for (BlockFace f : HORIZONTAL) {
                    Block n = b.getRelative(f);
                    // A hook faces away from the block it hangs on.
                    if (n.getType() == Material.TRIPWIRE_HOOK && n.getBlockData() instanceof TripwireHook h
                            && h.getFacing() == f) {
                        detachLine(n, removal);
                    }
                }
            }
            removal.gone.add(b);
        }
    }

    // ------------------------------------------------------------------ the 1.21.1 logic

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** What has already happened earlier in one event (an explosion removes many blocks in a row). */
    static final class Removal {
        final boolean explosion;
        /** Hooks whose attached state changed earlier in this event. */
        final Map<Block, Boolean> attached = new HashMap<>();
        /** Blocks already removed earlier in this event. */
        final Set<Block> gone = new HashSet<>();

        Removal(boolean explosion) {
            this.explosion = explosion;
        }
    }

    private void detachLine(Block hookBlock, Removal removal) {
        if (!(hookBlock.getBlockData() instanceof TripwireHook hook)) return;
        removal.attached.put(hookBlock, false);
        Block other = farHook(hookBlock, hook.getFacing());
        if (other != null) removal.attached.put(other, false);
    }

    /** The hook at the other end of the line, facing back, or null. */
    private static Block farHook(Block hookBlock, BlockFace facing) {
        for (int j = 1; j < MAX_LINE; j++) {
            Block b = hookBlock.getRelative(facing, j);
            if (b.getType() == Material.TRIPWIRE_HOOK) {
                return b.getBlockData() instanceof TripwireHook o && o.getFacing() == facing.getOppositeFace() ? b : null;
            }
        }
        return null;
    }

    private static boolean isString(Block b, Removal removal) {
        return b.getType() == Material.TRIPWIRE && !removal.gone.contains(b);
    }

    /**
     * Called just before a string is destroyed. Mirrors 1.21.1's TripWireBlock#updateSource, which
     * runs TripWireHookBlock#calculateState for the hook south and the hook west of the string.
     */
    void removed(Block wire, boolean disarmedByShears, Removal removal) {
        if (!stringDupe || wire.getType() != Material.TRIPWIRE) return;
        if (!(wire.getBlockData() instanceof Tripwire current)) return;
        Tripwire state = (Tripwire) current.clone();
        if (disarmedByShears) state.setDisarmed(true);
        // The hooks are handed the string's state with powered=true (it's "triggered" by its removal).
        state.setPowered(true);

        Boolean putBackAttached = null;
        for (BlockFace dir : new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST}) {
            for (int i = 1; i < MAX_LINE; i++) {
                Block b = wire.getRelative(dir, i);
                if (b.getType() == Material.TRIPWIRE_HOOK) {
                    if (b.getBlockData() instanceof TripwireHook hook && hook.getFacing() == dir.getOppositeFace()) {
                        Boolean result = calculateState(b, hook, i, state, removal);
                        if (result != null && putBackAttached == null) putBackAttached = result;
                    }
                    break;
                }
                if (!isString(b, removal)) break;
            }
        }
        if (putBackAttached == null) return;
        for (Pending p : pending) if (p.block().equals(wire)) return; // already queued this tick
        state.setAttached(putBackAttached);
        pending.add(new Pending(wire, state, removal.explosion));
    }

    /**
     * TripWireHookBlock#calculateState(level, hookPos, hookState, false, true, removedAt, state).
     * Returns the "attached" value the removed string is put back with, or null if this hook
     * doesn't put it back.
     */
    private Boolean calculateState(Block hookBlock, TripwireHook hook, int removedAt, Tripwire state, Removal removal) {
        boolean wasAttached = removal.attached.getOrDefault(hookBlock, hook.isAttached());
        BlockFace facing = hook.getFacing();
        boolean attached = true; // flag2: stays attached only if the whole line is intact and armed
        int far = 0;
        for (int j = 1; j < MAX_LINE; j++) {
            Block b = hookBlock.getRelative(facing, j);
            if (b.getType() == Material.TRIPWIRE_HOOK) {
                if (b.getBlockData() instanceof TripwireHook o && o.getFacing() == facing.getOppositeFace()) far = j;
                break;
            }
            if (j == removedAt) {
                if (state.isDisarmed()) attached = false;
            } else if (!isString(b, removal)) {
                attached = false; // a gap in the line
            }
        }
        attached &= far > 1;
        if (wasAttached == attached) return null; // nothing changes, nothing is re-set
        // The line changes state: both hooks update, and every string between them is re-set,
        // including the one that was just removed (the bug Mojang fixed in 1.21.2).
        removal.attached.put(hookBlock, attached);
        if (far > 0) removal.attached.put(hookBlock.getRelative(facing, far), attached);
        return far > removedAt ? attached : null;
    }

    private static boolean canReplace(Block b, boolean explosion) {
        Material type = b.getType();
        if (type.isAir()) return true;
        // An explosion can start a fire in the empty spot later in the same tick; in 1.21.1 the
        // string was already back by then.
        if (explosion && (type == Material.FIRE || type == Material.SOUL_FIRE)) return true;
        if ((type == Material.WATER || type == Material.LAVA)
                && b.getBlockData() instanceof org.bukkit.block.data.Levelled fluid) {
            return fluid.getLevel() != 0; // flowing, not a source
        }
        return false;
    }

    /** Strings waiting to be put back at the end of this tick (for tests). */
    List<Block> pendingBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (Pending p : pending) blocks.add(p.block());
        return blocks;
    }

    BlockData pendingState(Block block) {
        for (Pending p : pending) if (p.block().equals(block)) return p.state();
        return null;
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        if (pending.isEmpty()) return;
        List<Pending> batch = new ArrayList<>(pending);
        pending.clear();
        for (Pending p : batch) {
            Block b = p.block();
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            // Only where the string is really gone and nothing else took its place: air (broken,
            // exploded) or flowing water/lava. Never over a piston's moving block or a water source.
            if (!canReplace(b, p.explosion())) continue;
            b.setBlockData(p.state(), true);
        }
    }
}

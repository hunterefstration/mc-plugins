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
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import java.util.ArrayList;
import java.util.HashSet;
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
 *   module re-creates the exact 1.21.1 behaviour: when a <i>disarmed</i> string in an attached
 *   hook-to-hook line is destroyed (by water, a piston, an explosion, a player...), the hooks
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
    /** Hooks that have already detached this tick, so the same line can't dupe twice in one update. */
    private final Set<Block> detachedThisTick = new HashSet<>();

    private record Pending(Block block, BlockData state) {}

    public DupersModule(SurvivalCore plugin) {
        super(plugin, "dupers", "Dupers");
    }

    @Override
    public void enable() {
        pending.clear();
        detachedThisTick.clear();
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
        detachedThisTick.clear();
    }

    // ------------------------------------------------------------------ ways a string gets destroyed

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        removed(event.getToBlock(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // Paper lists the blocks a piston will break along with the ones it moves.
        for (Block b : event.getBlocks()) removed(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) removed(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // Vanilla marks string as disarmed when it's broken with shears, just before removing it.
        boolean shears = event.getPlayer().getInventory().getItemInMainHand().getType() == Material.SHEARS;
        removed(event.getBlock(), shears && shearDupe);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) removed(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) removed(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        removed(event.getBlock(), false);
    }

    // ------------------------------------------------------------------ the 1.21.1 logic

    /**
     * Called just before a block is destroyed. Mirrors TripWireBlock#updateSource followed by
     * TripWireHookBlock#calculateState from 1.21.1, which re-placed the string when a disarmed
     * one was removed from an attached line.
     */
    void removed(Block wire, boolean disarmedByShears) {
        if (!stringDupe || wire.getType() != Material.TRIPWIRE) return;
        if (!(wire.getBlockData() instanceof Tripwire current)) return;
        Tripwire state = (Tripwire) current.clone();
        if (disarmedByShears) state.setDisarmed(true);
        if (!state.isDisarmed()) return; // an armed string just breaks, like vanilla
        // The hooks were handed the string's state with powered=true (it's being "triggered" by its removal).
        state.setPowered(true);

        for (BlockFace dir : new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST}) {
            for (int i = 1; i < MAX_LINE; i++) {
                Block b = wire.getRelative(dir, i);
                if (b.getType() == Material.TRIPWIRE_HOOK) {
                    if (b.getBlockData() instanceof TripwireHook hook && hook.getFacing() == dir.getOppositeFace()
                            && wouldReplace(b, hook, i)) {
                        state.setAttached(false);
                        pending.add(new Pending(wire, state));
                        return;
                    }
                    break;
                }
                if (b.getType() != Material.TRIPWIRE) break;
            }
        }
    }

    /**
     * TripWireHookBlock#calculateState with searchRange = {@code removedAt}: the hook was attached,
     * and is now detaching (a disarmed string never counts as attached), so it re-sets every string
     * between itself and the hook on the other end.
     */
    private boolean wouldReplace(Block hookBlock, TripwireHook hook, int removedAt) {
        if (!hook.isAttached() || detachedThisTick.contains(hookBlock)) return false;
        BlockFace facing = hook.getFacing();
        for (int j = 1; j < MAX_LINE; j++) {
            Block b = hookBlock.getRelative(facing, j);
            if (b.getType() == Material.TRIPWIRE_HOOK) {
                if (!(b.getBlockData() instanceof TripwireHook other) || other.getFacing() != facing.getOppositeFace()) {
                    return false;
                }
                if (j <= removedAt) return false;
                detachedThisTick.add(hookBlock);
                detachedThisTick.add(b);
                return true;
            }
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
        detachedThisTick.clear();
        if (pending.isEmpty()) return;
        List<Pending> batch = new ArrayList<>(pending);
        pending.clear();
        for (Pending p : batch) {
            // Only if it really got destroyed (another plugin could still have stopped it).
            if (p.block().getType() == Material.TRIPWIRE) continue;
            if (!p.block().getChunk().isLoaded()) continue;
            p.block().setBlockData(p.state(), true);
        }
    }
}

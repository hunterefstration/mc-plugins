package dev.survivalcore.vaults;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Vault;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Trial chamber vaults (normal and ominous) can be opened again with another key. Vanilla only
 * lets each player loot a vault once; this takes players back off a vault's "already rewarded"
 * list, right away or after a configurable cooldown.
 */
public final class RevaultingModule extends Module {

    /** Vanilla vaults notice players within 4 blocks. */
    private static final double RANGE = 5;

    private BukkitTask task;
    private long delayNormal;
    private long delayOminous;

    public RevaultingModule(SurvivalCore plugin) {
        super(plugin, "revaulting", "Revaulting");
    }

    @Override
    public void enable() {
        delayNormal = Math.max(0, config().getLong("cooldown-ticks.vault", 0));
        delayOminous = Math.max(0, config().getLong("cooldown-ticks.ominous-vault", 0));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        Set<Location> done = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!world.isChunkLoaded(cx + dx, cz + dz)) continue;
                    Chunk chunk = world.getChunkAt(cx + dx, cz + dz);
                    for (BlockState state : chunk.getTileEntities(b -> b.getType() == Material.VAULT, false)) {
                        if (!(state instanceof Vault vault)) continue;
                        Location at = state.getLocation();
                        if (at.distanceSquared(loc) > RANGE * RANGE * 4 || !done.add(at)) continue;
                        reset(vault, world.getFullTime());
                    }
                }
            }
        }
    }

    /** Lets everyone whose cooldown is over use this vault again. */
    private void reset(Vault vault, long now) {
        Set<UUID> rewarded = new HashSet<>(vault.getRewardedPlayers());
        if (rewarded.isEmpty()) return;
        long delay = vault.getBlockData() instanceof org.bukkit.block.data.type.Vault data && data.isOminous()
                ? delayOminous : delayNormal;
        PersistentDataContainer pdc = vault.getPersistentDataContainer();
        boolean changed = false;
        for (UUID id : rewarded) {
            NamespacedKey key = new NamespacedKey(plugin, "revault_" + id.toString().replace("-", ""));
            if (delay > 0) {
                Long since = pdc.get(key, PersistentDataType.LONG);
                if (since == null) {
                    pdc.set(key, PersistentDataType.LONG, now);
                    changed = true;
                    continue;
                }
                if (now - since < delay) continue;
                pdc.remove(key);
            }
            vault.removeRewardedPlayer(id);
            changed = true;
        }
        if (changed) vault.update(true, false);
    }
}

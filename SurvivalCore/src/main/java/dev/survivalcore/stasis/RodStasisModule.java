package dev.survivalcore.stasis;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Fishing rod stasis. In vanilla your bobber stays where it landed (holding down a pressure plate
 * or tripwire in your stasis chamber) while you go to another dimension, and reeling in from there
 * lets go of the plate and fires the chamber. Paper deletes the bobber as soon as you change
 * worlds, which fires the chamber early. This module swaps the bobber for an invisible stand-in
 * that keeps the plate pressed until you reel in with your rod, from anywhere.
 */
public final class RodStasisModule extends Module {

    private final NamespacedKey ownerKey;
    private final File file;
    /** player -> where their stand-in is. */
    private final Map<UUID, Stasis> active = new HashMap<>();

    private record Stasis(UUID world, int chunkX, int chunkZ, UUID standIn) {}

    public RodStasisModule(SurvivalCore plugin) {
        super(plugin, "rod-stasis", "RodStasis");
        this.ownerKey = new NamespacedKey(plugin, "stasis_owner");
        this.file = new File(plugin.getDataFolder(), "stasis.yml");
    }

    @Override
    public void enable() {
        active.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            try {
                active.put(UUID.fromString(key), new Stasis(UUID.fromString(s.getString("world")),
                        s.getInt("chunk-x"), s.getInt("chunk-z"), UUID.fromString(s.getString("stand-in"))));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("stasis.yml: skipping bad entry " + key);
            }
        }
    }

    @Override
    public void disable() {
        save();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Stasis> e : active.entrySet()) {
            String path = "players." + e.getKey();
            data.set(path + ".world", e.getValue().world().toString());
            data.set(path + ".chunk-x", e.getValue().chunkX());
            data.set(path + ".chunk-z", e.getValue().chunkZ());
            data.set(path + ".stand-in", e.getValue().standIn().toString());
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save stasis.yml", e);
        }
    }

    // ------------------------------------------------------------------ keeping the bobber

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        changingWorld(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        changingWorld(event.getPlayer(), event.getFrom(), event.getTo());
    }

    private void changingWorld(Player player, Location from, Location to) {
        if (to == null || from.getWorld() == null || from.getWorld().equals(to.getWorld())) return;
        FishHook hook = player.getFishHook();
        if (hook == null || !hook.isValid() || hook.getHookedEntity() != null) return;
        if (!hook.isOnGround() && hook.getState() != FishHook.HookState.BOBBING) return; // still flying

        Location at = hook.getLocation();
        removeStandIn(player.getUniqueId()); // only one at a time, like one bobber
        ArmorStand stand = at.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setBasePlate(false);
            s.setCanTick(false);
            s.setCollidable(false);
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        });
        hook.remove();
        active.put(player.getUniqueId(), new Stasis(at.getWorld().getUID(), at.getBlockX() >> 4, at.getBlockZ() >> 4,
                stand.getUniqueId()));
        save();
    }

    // ------------------------------------------------------------------ reeling in

    @EventHandler(priority = EventPriority.HIGH)
    public void onRodUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FISHING_ROD) return;
        Player player = event.getPlayer();
        if (!active.containsKey(player.getUniqueId()) || player.getFishHook() != null) return;

        event.setCancelled(true);
        removeStandIn(player.getUniqueId());
        save();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 1f);
        // A bobber resting on a block costs 2 durability to reel in, same as vanilla.
        EquipmentSlot hand = event.getHand() == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        player.getInventory().setItem(hand, item.damage(2, player));
    }

    /** Removes a player's stand-in, loading its chunk for a few seconds so the chamber can fire. */
    private void removeStandIn(UUID owner) {
        Stasis stasis = active.remove(owner);
        if (stasis == null) return;
        World world = Bukkit.getWorld(stasis.world());
        if (world == null) return;
        Chunk chunk = world.getChunkAt(stasis.chunkX(), stasis.chunkZ());
        chunk.addPluginChunkTicket(plugin);
        Entity entity = Bukkit.getEntity(stasis.standIn());
        if (entity != null) {
            entity.remove();
        } else {
            for (Entity e : chunk.getEntities()) { // waits for the chunk's entities to load
                if (e.getUniqueId().equals(stasis.standIn())) e.remove();
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> chunk.removePluginChunkTicket(plugin), 100L);
    }

    /** A stand-in whose owner already reeled in (e.g. while its chunk was unloaded) gets cleaned up. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            String owner = e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (owner == null) continue;
            Stasis s;
            try {
                s = active.get(UUID.fromString(owner));
            } catch (IllegalArgumentException ex) {
                s = null;
            }
            if (s == null || !s.standIn().equals(e.getUniqueId())) e.remove();
        }
    }

    // ------------------------------------------------------------------ the stand-in is untouchable

    private boolean isStandIn(Entity e) {
        return e.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (isStandIn(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (isStandIn(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isStandIn(event.getEntity()) && event.getCause() != EntityDamageEvent.DamageCause.KILL) event.setCancelled(true);
    }
}

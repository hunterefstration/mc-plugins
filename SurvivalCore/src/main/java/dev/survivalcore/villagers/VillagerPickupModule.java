package dev.survivalcore.villagers;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.Names;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Sneak + right-click a villager to pick it up as an item; right-click a block with that item to
 * put it back down. Everything about the villager is kept (trades, level, XP, profession, name,
 * gossip/discounts, its UUID), because the whole entity is saved into the item.
 *
 * <p>Each item carries a one-time ticket that's recorded on the server, so a copy of the item
 * (creative middle-click) or a forged one can't spawn anything, and the world and the player are
 * saved straight away on pickup and placement so a crash can't duplicate or lose a villager.
 */
public final class VillagerPickupModule extends Module {

    private final NamespacedKey dataKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey tokenKey;
    private final File ticketFile;
    /** Outstanding tickets: ticket -> UUID of the villager inside that item. */
    private final Map<UUID, UUID> tickets = new HashMap<>();
    /**
     * Villagers placed since the world was last saved. If the server crashes before that, the
     * villager is put back from this record (the item is already used up).
     */
    private record Placed(UUID entity, UUID world, int x, int y, int z, String data, long placedAt) {
        boolean fromLastRun() {
            return placedAt < 0;
        }
    }
    private final Map<UUID, Placed> placed = new HashMap<>();
    /**
     * Villagers picked up whose old spot hasn't been saved yet: if the server crashes, the old copy
     * loads again there and is removed (even if its item was placed already).
     */
    private record Spot(UUID world, long pickedAt, UUID cleanedIn, long cleanedAt) {
        boolean fromLastRun() {
            return pickedAt < 0;
        }
    }
    /** World -> tick of its last flushed save in this run. */
    private final Map<UUID, Long> lastSaved = new HashMap<>();
    private final Map<UUID, Spot> pickedUp = new HashMap<>();
    private final Set<UUID> saveScheduled = new HashSet<>();
    private final Set<EntityType> allowed = new HashSet<>();
    private boolean requireSneak;
    private boolean allowBabies;

    public VillagerPickupModule(SurvivalCore plugin) {
        super(plugin, "villager-pickup", "VillagerPickup");
        this.dataKey = new NamespacedKey(plugin, "picked_entity");
        this.typeKey = new NamespacedKey(plugin, "picked_type");
        this.tokenKey = new NamespacedKey(plugin, "picked_ticket");
        this.ticketFile = new File(plugin.getDataFolder(), "picked-up-villagers.yml");
    }

    @Override
    public void enable() {
        requireSneak = config().getBoolean("require-sneak", true);
        allowBabies = config().getBoolean("allow-babies", true);
        allowed.clear();
        for (String name : config().getStringList("entities")) {
            try {
                allowed.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("villager-pickup.entities: unknown entity '" + name + "'");
            }
        }
        tickets.clear();
        placed.clear();
        pickedUp.clear();
        saveScheduled.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ticketFile);
        var pickedSection = yaml.getConfigurationSection("picked");
        if (pickedSection != null) {
            for (String key : pickedSection.getKeys(false)) {
                var p = pickedSection.getConfigurationSection(key);
                try {
                    // From before a restart: whatever was removed in memory then may not be on disk.
                    pickedUp.put(UUID.fromString(key), new Spot(UUID.fromString(p.getString("world")), -1, null, -1));
                } catch (RuntimeException ignored) {
                }
            }
        }
        var placedSection = yaml.getConfigurationSection("placed");
        if (placedSection != null) {
            for (String key : placedSection.getKeys(false)) {
                var p = placedSection.getConfigurationSection(key);
                try {
                    placed.put(UUID.fromString(key), new Placed(UUID.fromString(p.getString("entity")),
                            UUID.fromString(p.getString("world")), p.getInt("x"), p.getInt("y"), p.getInt("z"),
                            p.getString("data"), -1));
                } catch (RuntimeException ignored) {
                }
            }
        }
        var section = yaml.getConfigurationSection("tickets");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    tickets.put(UUID.fromString(key), UUID.fromString(section.getString(key, "")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // Chunks loaded before this ran (spawn chunks, forceloaded ones) get the same checks.
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            checkLoaded(w.getEntities());
            for (org.bukkit.Chunk c : w.getLoadedChunks()) recoverPlaced(c);
        }
    }

    private void saveTickets() {
        YamlConfiguration yaml = new YamlConfiguration();
        tickets.forEach((t, e) -> yaml.set("tickets." + t, e.toString()));
        pickedUp.forEach((e, spot) -> {
            String path = "picked." + e;
            yaml.set(path + ".world", spot.world().toString());
        });
        placed.forEach((t, p) -> {
            String path = "placed." + t;
            yaml.set(path + ".entity", p.entity().toString());
            yaml.set(path + ".world", p.world().toString());
            yaml.set(path + ".x", p.x());
            yaml.set(path + ".y", p.y());
            yaml.set(path + ".z", p.z());
            yaml.set(path + ".data", p.data());
        });
        try {
            yaml.save(ticketFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + ticketFile.getName(), e);
        }
    }

    // ------------------------------------------------------------------ picking up

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        // Never let our item act as a normal spawn egg on a mob, from either hand.
        if (isPickedUp(player.getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity target = event.getRightClicked();

        if (!allowed.contains(target.getType()) || !(target instanceof LivingEntity living)) return;
        if (requireSneak && !player.isSneaking()) return;
        if (!player.getInventory().getItemInMainHand().getType().isAir()) return; // empty hand only
        if (!player.hasPermission("survivalcore.villagerpickup")) return;
        if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) return;
        if (living.isDead() || !living.isValid()) return;
        if (!allowBabies && target instanceof org.bukkit.entity.Ageable a && !a.isAdult()) return;
        if (target instanceof Merchant merchant && merchant.isTrading()) {
            player.sendMessage(prefix() + ChatColor.RED + "Someone is trading with that villager.");
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (player.getInventory().firstEmpty() == -1) {
            // Never drop it on the ground, where it could burn or despawn.
            player.sendMessage(prefix() + ChatColor.RED + "Make room in your inventory first.");
            return;
        }

        // A sleeping villager would snap back to its old bed when placed, and leave that bed taken.
        if (target instanceof Villager sleeper && sleeper.isSleeping()) sleeper.wakeup();
        if (!target.getPassengers().isEmpty()) target.eject();
        if (target.isInsideVehicle()) target.leaveVehicle();
        if (living.isLeashed()) {
            living.setLeashHolder(null); // this doesn't drop the lead by itself, so give it back
            target.getWorld().dropItemNaturally(target.getLocation(), new ItemStack(Material.LEAD));
        }

        byte[] data;
        try {
            data = Bukkit.getUnsafe().serializeEntity(target);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not pick up " + target.getType(), t);
            player.sendMessage(prefix() + ChatColor.RED + "Couldn't pick that up.");
            return;
        }
        UUID ticket = UUID.randomUUID();
        tickets.put(ticket, target.getUniqueId());
        Location was = target.getLocation();
        pickedUp.put(target.getUniqueId(), new Spot(was.getWorld().getUID(), Bukkit.getCurrentTick(), null, -1));
        // A villager placed earlier (still waiting for its save) is in the item now, not in the world.
        placed.values().removeIf(p -> p.entity().equals(target.getUniqueId()));
        saveTickets();
        ItemStack item = createItem(target, data, ticket);
        target.remove();
        player.getInventory().addItem(item);
        // Save the player now (cheap) and the world shortly. If the server crashes before the
        // villager's old spot is saved, the old copy loads again and is removed: the item is the real one.
        player.saveData();
        // An old copy could be on disk in any world (it may have used a portal since its last
        // save), so the pickup only counts as saved once every world has been saved.
        for (org.bukkit.World w : Bukkit.getWorlds()) saveWorldSoon(w, PICKUP_SAVE_DELAY);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
    }

    private ItemStack createItem(Entity entity, byte[] data, UUID token) {
        Material egg = Material.matchMaterial(entity.getType().name() + "_SPAWN_EGG");
        ItemStack item = new ItemStack(egg != null ? egg : Material.VILLAGER_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();

        String typeName = Names.pretty(entity.getType().name());
        List<String> lore = new ArrayList<>();
        String title = typeName;
        if (entity instanceof Villager v) {
            title = v.getProfession() == Villager.Profession.NONE ? "Villager"
                    : Names.pretty(v.getProfession().getKey().getKey());
            lore.add(ChatColor.GRAY + "Level: " + ChatColor.WHITE + levelName(v.getVillagerLevel()));
            lore.add(ChatColor.GRAY + "Biome type: " + ChatColor.WHITE + Names.pretty(v.getVillagerType().getKey().getKey()));
        } else if (entity instanceof ZombieVillager z) {
            lore.add(ChatColor.GRAY + "Profession: " + ChatColor.WHITE + Names.pretty(z.getVillagerProfession().getKey().getKey()));
        }
        if (entity instanceof org.bukkit.entity.Ageable a && !a.isAdult()) title = "Baby " + title;
        if (entity instanceof Merchant merchant && !merchant.getRecipes().isEmpty()) {
            lore.add(ChatColor.GRAY + "Trades:");
            int shown = 0;
            for (MerchantRecipe recipe : merchant.getRecipes()) {
                if (shown++ == 8) {
                    lore.add(ChatColor.DARK_GRAY + "  ...and " + (merchant.getRecipes().size() - 8) + " more");
                    break;
                }
                lore.add(ChatColor.DARK_GRAY + "  - " + ChatColor.WHITE + describe(recipe.getResult()));
            }
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Right-click a block to place");

        String custom = entity.getCustomName();
        meta.setItemName(ChatColor.GREEN + (custom != null ? ChatColor.stripColor(custom) + " (" + title + ")" : title));
        meta.setLore(lore);
        meta.setMaxStackSize(1);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY, data);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, entity.getType().name());
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token.toString());
        item.setItemMeta(meta);
        return item;
    }

    private static String levelName(int level) {
        return switch (level) {
            case 1 -> "Novice";
            case 2 -> "Apprentice";
            case 3 -> "Journeyman";
            case 4 -> "Expert";
            case 5 -> "Master";
            default -> String.valueOf(level);
        };
    }

    private static String describe(ItemStack item) {
        String name = item.getAmount() + "x " + Names.pretty(item.getType().name());
        if (item.getItemMeta() instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()) {
            List<String> enchants = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> e : book.getStoredEnchants().entrySet()) {
                String key = Names.pretty(e.getKey().getKey().getKey());
                enchants.add(e.getKey().getMaxLevel() > 1 ? key + " " + e.getValue() : key);
            }
            name += " (" + String.join(", ", enchants) + ")";
        }
        return name;
    }

    public boolean isPickedUp(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(dataKey, PersistentDataType.BYTE_ARRAY);
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isPickedUp(item)) return;
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR || action == Action.PHYSICAL) return;
        // A protection plugin (claims) may already have said no to using items here.
        boolean deniedByOthers = event.useItemInHand() == Event.Result.DENY;
        // From here on it must never behave like a normal spawn egg (e.g. changing a spawner).
        event.setUseItemInHand(Event.Result.DENY);
        if (deniedByOthers) return;
        if (action != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() == null) return;
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        // Right-clicking a chest, door, button... uses that block, like any other item would.
        if (clicked.getType().isInteractable() && !player.isSneaking()) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) return;

        Block target = clicked.isPassable() && !clicked.isLiquid() ? clicked : clicked.getRelative(event.getBlockFace());
        if (!target.isPassable() || !target.getRelative(0, 1, 0).isPassable()) {
            player.sendMessage(prefix() + ChatColor.RED + "There isn't enough room there.");
            return;
        }
        Location at = target.getLocation().add(0.5, 0, 0.5);
        at.setYaw(player.getLocation().getYaw() + 180);

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        byte[] data = pdc.get(dataKey, PersistentDataType.BYTE_ARRAY);
        String ticketText = pdc.get(tokenKey, PersistentDataType.STRING);
        UUID ticket;
        try {
            ticket = ticketText == null ? null : UUID.fromString(ticketText);
        } catch (IllegalArgumentException e) {
            ticket = null;
        }
        UUID expected = ticket == null ? null : tickets.get(ticket);
        if (expected == null) {
            // A copy (creative middle-click) or a forged item: there's no villager behind it.
            player.sendMessage(prefix() + ChatColor.RED + "This villager item isn't valid (it's a copy), so it was removed.");
            removeFromHand(player, event.getHand());
            return;
        }

        Entity entity;
        try {
            entity = Bukkit.getUnsafe().deserializeEntity(data, target.getWorld(), true);
            if (!allowed.contains(entity.getType()) || !entity.getType().name().equals(pdc.get(typeKey, PersistentDataType.STRING))
                    || !entity.getUniqueId().equals(expected)) {
                player.sendMessage(prefix() + ChatColor.RED + "This villager item isn't valid.");
                return;
            }
            // A leftover copy (left by a crash before its removal was saved) goes: the item is the real one.
            Entity leftover = Bukkit.getEntity(entity.getUniqueId());
            if (leftover != null) leftover.remove();
            // While its old spot isn't saved yet, the placed one gets a new UUID, so an old copy
            // that turns up after a crash can be told apart and removed.
            if (pickedUp.containsKey(entity.getUniqueId()) || Bukkit.getEntity(entity.getUniqueId()) != null) {
                entity = Bukkit.getUnsafe().deserializeEntity(data, target.getWorld(), false);
            }
            if (!entity.spawnAt(at, CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) {
                player.sendMessage(prefix() + ChatColor.RED + "Something stopped it from being placed here.");
                return;
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not place a picked-up entity", t);
            player.sendMessage(prefix() + ChatColor.RED + "Couldn't place it.");
            return;
        }

        // Always used up, even in creative, so villagers can't be copied.
        removeFromHand(player, event.getHand());
        tickets.remove(ticket);
        placed.put(ticket, new Placed(entity.getUniqueId(), target.getWorld().getUID(), at.getBlockX(), at.getBlockY(),
                at.getBlockZ(), java.util.Base64.getEncoder().encodeToString(data), Bukkit.getCurrentTick()));
        saveTickets();
        player.saveData();
        saveWorldSoon(target.getWorld(), PLACE_SAVE_DELAY);
        player.playSound(at, Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1f);
    }

    private static void removeFromHand(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
        else player.getInventory().setItemInMainHand(null);
    }

    /** Dispensers would spawn a brand-new villager from it (a free villager); droppers just move it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isPickedUp(event.getItem()) && event.getBlock().getType() == Material.DISPENSER) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ never lose one

    /** If the item is ever on the ground it can't burn, explode or despawn. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!isPickedUp(event.getEntity().getItemStack())) return;
        event.getEntity().setUnlimitedLifetime(true);
        event.getEntity().setInvulnerable(true);
    }

    /** Fell into the void: put the villager back at the world spawn instead of losing it. */
    @EventHandler
    public void onItemRemoved(EntityRemoveEvent event) {
        if (event.getCause() != EntityRemoveEvent.Cause.OUT_OF_WORLD) return;
        if (!(event.getEntity() instanceof Item item) || !isPickedUp(item.getItemStack())) return;
        ItemStack stack = item.getItemStack();
        Location spawn = item.getWorld().getSpawnLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Item rescued = spawn.getWorld().dropItem(spawn, stack);
            rescued.setUnlimitedLifetime(true);
            rescued.setInvulnerable(true);
        });
    }

    /**
     * Picked-up villagers are protected by their UUID wherever an old copy turns up, so their save
     * can wait. A placed one should be on disk soon, before it can wander into another chunk.
     */
    private static final long PICKUP_SAVE_DELAY = 6000L;
    private static final long PLACE_SAVE_DELAY = 200L;
    /** World -> scheduled save (task, and the tick it runs at). */
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> saveTasks = new HashMap<>();
    private final Map<UUID, Long> saveAt = new HashMap<>();

    /** One world save after pickups/placements, coalesced (a sooner request moves it earlier). */
    private void saveWorldSoon(org.bukkit.World world, long delay) {
        UUID id = world.getUID();
        long when = Bukkit.getCurrentTick() + delay;
        Long existing = saveAt.get(id);
        if (existing != null && existing <= when) return;
        org.bukkit.scheduler.BukkitTask old = saveTasks.remove(id);
        if (old != null) old.cancel();
        saveScheduled.add(id);
        saveAt.put(id, when);
        saveTasks.put(id, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            saveScheduled.remove(id);
            saveAt.remove(id);
            saveTasks.remove(id);
            saveNow(world);
        }, delay));
    }

    /** Saves the world (waiting for the files to be written) and forgets what is now safely on disk. */
    private void saveNow(org.bukkit.World world) {
        world.save(true);
        UUID id = world.getUID();
        boolean changed = false;
        // A flushed save writes every chunk's entities, so this run's pickups are on disk now; an
        // old copy from before a crash is too, once it has been seen and removed.
        lastSaved.put(id, (long) Bukkit.getCurrentTick());
        changed |= pickedUp.values().removeIf(this::safeOnDisk);
        for (Map.Entry<UUID, Placed> e : new ArrayList<>(placed.entrySet())) {
            Placed p = e.getValue();
            if (p.fromLastRun()) continue; // checked when it loads
            Entity now = Bukkit.getEntity(p.entity());
            boolean safe;
            if (now != null) {
                // Saved wherever it is now; if it went to another world, save that one soon too.
                safe = now.getWorld().getUID().equals(id);
                if (!safe) saveWorldSoon(now.getWorld(), PLACE_SAVE_DELAY);
            } else {
                // Not loaded (its chunk unloaded): safe once every world has been saved since it was placed.
                safe = true;
                for (org.bukkit.World w : Bukkit.getWorlds()) {
                    if (lastSaved.getOrDefault(w.getUID(), -1L) < p.placedAt()) {
                        safe = false;
                        saveWorldSoon(w, PLACE_SAVE_DELAY);
                    }
                }
            }
            if (safe) {
                placed.remove(e.getKey());
                changed = true;
            }
        }
        if (changed) saveTickets();
    }

    /**
     * A pickup from this run is on disk once every world has been saved since (wherever an old
     * copy was). An old copy from before a restart is gone from disk once it has been removed and
     * the world it was removed in has been saved since.
     */
    private boolean safeOnDisk(Spot spot) {
        if (spot.cleanedIn() != null) return lastSaved.getOrDefault(spot.cleanedIn(), -1L) >= spot.cleanedAt();
        if (spot.fromLastRun()) return false; // its old copy hasn't turned up yet: keep watching for it
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (lastSaved.getOrDefault(w.getUID(), -1L) < spot.pickedAt()) return false;
        }
        return true;
    }

    @Override
    public void disable() {
        // Pending saves are about to be cancelled; do them now.
        for (UUID id : new ArrayList<>(saveScheduled)) {
            org.bukkit.World w = Bukkit.getWorld(id);
            if (w != null) saveNow(w);
        }
        saveScheduled.clear();
        saveAt.clear();
        saveTasks.clear();
        saveTickets();
    }

    /** A placed villager that's saved or gone for real no longer needs recovering. */
    @EventHandler
    public void onEntityRemoved(EntityRemoveEvent event) {
        // Unloaded (saved with its chunk), killed, despawned...: nothing to recover any more.
        UUID id = event.getEntity().getUniqueId();
        // A record from before a crash still needs its check, unless the villager really went
        // (killed, despawned...). Being unloaded doesn't count: that may be the unsaved copy.
        boolean gone = event.getCause() != EntityRemoveEvent.Cause.UNLOAD;
        if (placed.values().removeIf(p -> p.entity().equals(id) && (gone || !p.fromLastRun()))) saveTickets();
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        checkLoaded(event.getEntities());
        // A placed villager that loads (wherever it walked to) was saved: no recovery needed.
        Set<UUID> loaded = new HashSet<>();
        for (Entity e : event.getEntities()) loaded.add(e.getUniqueId());
        boolean changed = placed.values().removeIf(p -> loaded.contains(p.entity()));
        if (changed) saveTickets();
        recoverPlaced(event.getChunk());
    }

    /**
     * After a crash, a picked-up villager whose removal never got saved would load again while
     * its item also exists; the item is the real one, so the leftover goes.
     */
    private void checkLoaded(List<Entity> entities) {
        if (tickets.isEmpty() && pickedUp.isEmpty()) return;
        Set<UUID> inItems = new HashSet<>(tickets.values());
        for (Entity e : entities) {
            if (!allowed.contains(e.getType())) continue;
            // Picked-up villagers' UUIDs are never given to a placed one until the pickup is saved,
            // so anything with such a UUID is an old copy, wherever it turns up.
            Spot spot = pickedUp.get(e.getUniqueId());
            if (inItems.contains(e.getUniqueId()) || spot != null) {
                e.remove();
                if (spot != null) {
                    // Removed in memory; safe once this world (where it was) has been saved.
                    pickedUp.put(e.getUniqueId(), new Spot(spot.world(), spot.pickedAt(), e.getWorld().getUID(), Bukkit.getCurrentTick()));
                    saveTickets();
                    saveWorldSoon(e.getWorld(), PICKUP_SAVE_DELAY);
                }
            }
        }
    }

    /** A villager placed just before a crash (never saved) is put back where it was placed. */
    private void recoverPlaced(org.bukkit.Chunk chunk) {
        for (Map.Entry<UUID, Placed> e : new ArrayList<>(placed.entrySet())) {
            Placed p = e.getValue();
            if (!p.world().equals(chunk.getWorld().getUID()) || (p.x() >> 4) != chunk.getX() || (p.z() >> 4) != chunk.getZ()) continue;
            UUID ticket = e.getKey();
            // Give neighbouring chunks a moment to load too, in case it had walked into one.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Placed still = placed.get(ticket);
                if (still == null || still != p) return; // already handled (by another check)
                org.bukkit.World w = Bukkit.getWorld(still.world());
                if (w == null) return;
                // Its chunk unloaded again meanwhile: check next time it's loaded instead.
                if (!w.isChunkLoaded(still.x() >> 4, still.z() >> 4) || !w.getChunkAt(still.x() >> 4, still.z() >> 4).isEntitiesLoaded()) return;
                placed.remove(ticket);
                saveTickets();
                if (Bukkit.getEntity(still.entity()) != null) return; // it was saved after all
                try {
                    byte[] bytes = java.util.Base64.getDecoder().decode(still.data());
                    Entity back = Bukkit.getUnsafe().deserializeEntity(bytes, w, true);
                    if (pickedUp.containsKey(back.getUniqueId())) back = Bukkit.getUnsafe().deserializeEntity(bytes, w, false);
                    Location at = new Location(w, still.x() + 0.5, still.y(), still.z() + 0.5);
                    if (!back.spawnAt(at, CreatureSpawnEvent.SpawnReason.CUSTOM)) {
                        back = Bukkit.getUnsafe().deserializeEntity(bytes, w, false);
                        if (!back.spawnAt(at, CreatureSpawnEvent.SpawnReason.CUSTOM)) throw new IllegalStateException("spawn was cancelled");
                    }
                    plugin.getLogger().info("Put back a villager that was placed just before the server stopped unexpectedly.");
                    placed.put(ticket, new Placed(back.getUniqueId(), still.world(), still.x(), still.y(), still.z(), still.data(),
                            Bukkit.getCurrentTick()));
                    saveTickets();
                    saveWorldSoon(w, PLACE_SAVE_DELAY);
                } catch (Throwable t) {
                    placed.put(ticket, still); // keep it, try again next time the chunk loads
                    saveTickets();
                    plugin.getLogger().log(Level.WARNING, "Could not put back a villager placed before a crash; will retry", t);
                }
            }, 40L);
        }
    }
}

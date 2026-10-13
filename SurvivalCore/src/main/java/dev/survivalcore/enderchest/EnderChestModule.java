package dev.survivalcore.enderchest;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.Holders;
import dev.survivalcore.util.Nbt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameEvent;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lidded;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bigger ender chests (up to 6 rows). The first 3 rows are the player's normal vanilla ender
 * chest, so nothing is lost if this is ever turned off. The extra rows are kept in the player's
 * own save data, so they're always saved together with their inventory (a crash can't dupe or
 * lose items between the two). Players coming from BetterEnderChest get their chest imported the
 * first time they open it.
 */
public final class EnderChestModule extends Module {

    private static final int VANILLA_SIZE = 27;

    private final NamespacedKey extraKey;
    private final NamespacedKey pendingKey;
    private final NamespacedKey importedKey;
    private final NamespacedKey importFailedKey;
    private final NamespacedKey angerUntilKey;
    private final NamespacedKey angerAtKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<Location, Integer> openLids = new HashMap<>();
    private final Map<UUID, Location> viewerChests = new HashMap<>();
    private final Set<UUID> pendingSaves = new HashSet<>();
    /** Players whose BetterEnderChest import already failed since the server started (don't retry every open). */
    private final Set<UUID> importTriedThisRun = new HashSet<>();
    private BukkitTask validityTask;

    /** One open ender chest, shared by everyone looking at it. */
    private final class Session implements InventoryHolder {
        final UUID owner;
        final Player ownerPlayer;
        Inventory inventory;
        /** The vanilla ender chest as we last wrote it, to spot changes made by anything else. */
        ItemStack[] lastVanilla = new ItemStack[VANILLA_SIZE];
        final Set<UUID> viewers = new HashSet<>();

        Session(Player owner) {
            this.owner = owner.getUniqueId();
            this.ownerPlayer = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public EnderChestModule(SurvivalCore plugin) {
        super(plugin, "ender-chest", "EnderChest");
        this.extraKey = new NamespacedKey(plugin, "ender_chest_extra");
        this.pendingKey = new NamespacedKey(plugin, "ender_chest_waiting");
        this.importedKey = new NamespacedKey(plugin, "ender_chest_bec_imported");
        this.importFailedKey = new NamespacedKey(plugin, "ender_chest_bec_failed");
        this.angerUntilKey = new NamespacedKey(plugin, "piglin_anger_until");
        this.angerAtKey = new NamespacedKey(plugin, "piglin_anger_at");
        // Always registered (even with this feature off): piglins we angered calm down when they
        // load after their time is up, instead of staying angry forever.
        plugin.getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @EventHandler
            public void onLoad(org.bukkit.event.world.EntitiesLoadEvent event) {
                boolean on = plugin.isEnabled(EnderChestModule.this);
                for (org.bukkit.entity.Entity e : event.getEntities()) {
                    if (!(e instanceof Piglin piglin)) continue;
                    if (!calmIfDue(piglin, !on) && on) angered.add(piglin.getUniqueId());
                }
            }
        }, plugin);
        plugin.bind("enderchest", this, new EnderChestCommand());
    }

    /** Switched off in config.yml: nothing will check the timers, so calm every piglin we angered now. */
    @Override
    public void turnedOff() {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Piglin piglin : w.getEntitiesByClass(Piglin.class)) calmIfDue(piglin, true);
        }
    }

    @Override
    public void enable() {
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Piglin piglin : w.getEntitiesByClass(Piglin.class)) {
                if (piglin.getPersistentDataContainer().has(angerUntilKey, PersistentDataType.LONG)) angered.add(piglin.getUniqueId());
            }
        }
        validityTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            checkStillValid();
            checkAnger();
        }, 10L, 10L);
    }

    @Override
    public void disable() {
        if (validityTask != null) validityTask.cancel();
        validityTask = null;
        for (Session s : new ArrayList<>(sessions.values())) {
            for (HumanEntity viewer : new ArrayList<>(s.inventory.getViewers())) viewer.closeInventory();
            save(s);
        }
        sessions.clear();
        for (Location lid : openLids.keySet()) setLid(lid, false);
        openLids.clear();
        viewerChests.clear();
        pendingSaves.clear();
        angered.clear(); // picked up again on enable (their end time is stored on them)
    }

    private int rowsFor(Player player) {
        int rows = config().getInt("rows", 6);
        for (int n = 6; n > rows; n--) {
            if (player.hasPermission("survivalcore.enderchest.rows." + n)) {
                rows = n;
                break;
            }
        }
        return Math.max(3, Math.min(6, rows));
    }

    // ------------------------------------------------------------------ opening

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickChest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (block.getType() != Material.ENDER_CHEST) return;
        Player player = event.getPlayer();
        // Sneaking with something in hand places/uses it instead, like vanilla.
        if (player.isSneaking() && (!player.getInventory().getItemInMainHand().getType().isAir()
                || !player.getInventory().getItemInOffHand().getType().isAir())) return;
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        // Vanilla: a solid block (in its current state, e.g. a double slab) on top keeps it shut.
        BlockState state = block.getState(false);
        if (state instanceof org.bukkit.block.EnderChest chest && chest.isBlocked()) return;
        if (open(player, player, block.getLocation())) {
            player.incrementStatistic(Statistic.ENDERCHEST_OPENED);
            angerPiglins(player);
        }
    }

    /** Other plugins (e.g. /ec commands) opening the vanilla ender chest get the big one instead. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!(Holders.of(event.getInventory()) instanceof Player owner)) return;
        if (!(event.getPlayer() instanceof Player viewer)) return;
        if (!event.getInventory().equals(owner.getEnderChest())) return;
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (owner.isOnline() && viewer.isOnline()) open(viewer, owner, null);
        });
    }

    private boolean open(Player viewer, Player owner, Location chest) {
        Session session = sessions.get(owner.getUniqueId());
        if (session == null) {
            session = new Session(owner);
            Inventory inv = load(owner, session);
            if (inv == null) return false;
            session.inventory = inv;
            sessions.put(owner.getUniqueId(), session);
        }
        if (viewer.openInventory(session.inventory) == null) { // another plugin stopped it
            if (session.viewers.isEmpty()) sessions.remove(owner.getUniqueId(), session);
            return false;
        }
        // Re-opening the same chest fires a close first, which may have dropped the session.
        sessions.put(owner.getUniqueId(), session);
        session.viewers.add(viewer.getUniqueId());
        if (chest != null) {
            viewerChests.put(viewer.getUniqueId(), chest);
            if (openLids.merge(chest, 1, Integer::sum) == 1) {
                // Like vanilla: the lid and sculk-sensor event only for the first one to open it.
                setLid(chest, true);
                chest.getWorld().sendGameEvent(viewer, GameEvent.CONTAINER_OPEN, chest.toVector().add(new Vector(0.5, 0.5, 0.5)));
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(Holders.of(event.getInventory()) instanceof Session session)) return;
        UUID viewer = event.getPlayer().getUniqueId();
        session.viewers.remove(viewer);
        Location chest = viewerChests.remove(viewer);
        if (chest != null) {
            int left = openLids.merge(chest, -1, Integer::sum);
            if (left <= 0) {
                openLids.remove(chest);
                setLid(chest, false);
                // ...and the close event for the last one, if the chest is still there.
                if (chest.getWorld() != null && chest.getWorld().isChunkLoaded(chest.getBlockX() >> 4, chest.getBlockZ() >> 4)
                        && chest.getBlock().getType() == Material.ENDER_CHEST) {
                    chest.getWorld().sendGameEvent(event.getPlayer(), GameEvent.CONTAINER_CLOSE,
                            chest.toVector().add(new Vector(0.5, 0.5, 0.5)));
                }
            }
        }
        save(session);
        if (session.viewers.isEmpty()) sessions.remove(session.owner, session);
    }

    /** Saves while the owner is still online (an admin might be looking at their chest). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        for (HumanEntity viewer : new ArrayList<>(session.inventory.getViewers())) viewer.closeInventory();
        save(session);
        sessions.remove(session.owner, session);
    }

    /** Like vanilla: the chest closes when it's broken or the player gets carried too far away. */
    private void checkStillValid() {
        for (Map.Entry<UUID, Location> e : new ArrayList<>(viewerChests.entrySet())) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            Location chest = e.getValue();
            if (viewer == null) continue;
            boolean gone = chest.getWorld() == null || !chest.getWorld().isChunkLoaded(chest.getBlockX() >> 4, chest.getBlockZ() >> 4)
                    || chest.getBlock().getType() != Material.ENDER_CHEST;
            boolean far = viewer.getWorld() != chest.getWorld()
                    || viewer.getLocation().distanceSquared(chest.clone().add(0.5, 0.5, 0.5)) > 64;
            if (gone || far) viewer.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestBroken(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.ENDER_CHEST) {
            plugin.getServer().getScheduler().runTask(plugin, this::checkStillValid);
        }
    }

    private static void setLid(Location at, boolean open) {
        if (at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        if (!(at.getBlock().getState() instanceof Lidded lidded)) return;
        if (open) lidded.open();
        else lidded.close();
    }

    /** Vanilla: piglins that see you open an ender chest get angry (Paper's piglins-guard-chests). */
    private void angerPiglins(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || !piglinsGuardChests(player)) return;
        boolean universal = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.UNIVERSAL_ANGER));
        for (Piglin piglin : player.getWorld().getNearbyEntitiesByType(Piglin.class, player.getLocation(), 16)) {
            if (piglin.getTarget() != null || piglin.isConverting() || !piglin.hasLineOfSight(player)) continue;
            Player target = universal ? nearestTargetable(piglin, player) : player;
            piglin.setMemory(MemoryKey.ANGRY_AT, target.getUniqueId());
            if (universal) piglin.setMemory(MemoryKey.UNIVERSAL_ANGER, true);
            piglin.setTarget(target);
            // Vanilla's anger wears off after 30 seconds (600 ticks). The end time is kept on the
            // piglin itself, so it still calms down if its chunk was unloaded meanwhile.
            piglin.getPersistentDataContainer().set(angerUntilKey, PersistentDataType.LONG, piglin.getWorld().getGameTime() + 600);
            piglin.getPersistentDataContainer().set(angerAtKey, PersistentDataType.STRING, target.getUniqueId().toString());
            angered.add(piglin.getUniqueId());
        }
    }

    /** Piglins we made angry that are still loaded (checked every half second). */
    private final Set<UUID> angered = new HashSet<>();

    private void checkAnger() {
        for (UUID id : new ArrayList<>(angered)) {
            if (!(Bukkit.getEntity(id) instanceof Piglin piglin) || !piglin.isValid()) {
                angered.remove(id); // unloaded: checked again when it loads
                continue;
            }
            if (calmIfDue(piglin, false)) angered.remove(id);
        }
    }

    /** Takes our anger off a piglin once it's due (or right away). Returns true when done. */
    private boolean calmIfDue(Piglin piglin, boolean now) {
        PersistentDataContainer pdc = piglin.getPersistentDataContainer();
        Long until = pdc.get(angerUntilKey, PersistentDataType.LONG);
        if (until == null) return true;
        if (!now && piglin.getWorld().getGameTime() < until) return false;
        String at = pdc.get(angerAtKey, PersistentDataType.STRING);
        UUID current = piglin.getMemory(MemoryKey.ANGRY_AT);
        if (at != null && current != null && at.equals(current.toString())) {
            piglin.setMemory(MemoryKey.ANGRY_AT, null);
            if (piglin.getTarget() != null && piglin.getTarget().getUniqueId().equals(current)) piglin.setTarget(null);
        }
        piglin.setMemory(MemoryKey.UNIVERSAL_ANGER, null);
        pdc.remove(angerUntilKey);
        pdc.remove(angerAtKey);
        return true;
    }

    private static Player nearestTargetable(Piglin piglin, Player fallback) {
        Player best = fallback;
        double bestDist = Double.MAX_VALUE;
        for (Player p : piglin.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(piglin.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Paper's per-world entities.behavior.piglins-guard-chests (true unless turned off). */
    private static boolean piglinsGuardChests(Player player) {
        try {
            Object level = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object config = level.getClass().getMethod("paperConfig").invoke(level);
            Object entities = config.getClass().getField("entities").get(config);
            Object behavior = entities.getClass().getField("behavior").get(entities);
            return behavior.getClass().getField("piglinsGuardChests").getBoolean(behavior);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ storage

    /** Builds the big inventory: vanilla ender chest first, then the extra rows, then anything waiting. */
    private Inventory load(Player owner, Session holder) {
        PersistentDataContainer pdc = owner.getPersistentDataContainer();
        List<ItemStack> items = new ArrayList<>(Arrays.asList(owner.getEnderChest().getContents()));
        List<ItemStack> waiting = new ArrayList<>();
        try {
            byte[] extra = pdc.get(extraKey, PersistentDataType.BYTE_ARRAY);
            if (extra != null) items.addAll(Arrays.asList(ItemStack.deserializeItemsFromBytes(extra)));
            byte[] pending = pdc.get(pendingKey, PersistentDataType.BYTE_ARRAY);
            if (pending != null) waiting.addAll(Arrays.asList(ItemStack.deserializeItemsFromBytes(pending)));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the extra ender chest rows of " + owner.getName()
                    + "; not opening it so nothing gets overwritten.", e);
            owner.sendMessage(prefix() + ChatColor.RED + "Your ender chest couldn't be loaded. Tell an admin.");
            return null;
        }

        boolean failedBefore = owner.getPersistentDataContainer().has(importFailedKey, PersistentDataType.BYTE);
        Map<Integer, ItemStack> fromBec = importBetterEnderChest(owner);
        if (fromBec != null && !failedBefore) {
            // BetterEnderChest's chest replaces the vanilla one, which it had left stale. Anything
            // already in the extra rows is kept (it waits for room) rather than thrown away.
            if (items.size() > VANILLA_SIZE) waiting.addAll(items.subList(VANILLA_SIZE, items.size()));
            items.clear();
            int max = fromBec.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            for (int i = 0; i <= max; i++) items.add(fromBec.get(i));
        } else if (fromBec != null) {
            // An earlier import failed and they've been using this chest since: keep it, add the old items.
            waiting.addAll(fromBec.values());
        }

        int rows = rowsFor(owner);
        for (int i = items.size() - 1; i >= 0; i--) { // never hide items: grow to fit what's stored
            if (!isEmpty(items.get(i))) {
                rows = Math.max(rows, Math.min(6, i / 9 + 1));
                break;
            }
        }
        Inventory inv = Bukkit.createInventory(holder, rows * 9, config().getString("title", "Ender Chest"));
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (isEmpty(item)) continue;
            if (i < inv.getSize()) inv.setItem(i, item);
            else waiting.add(item);
        }
        // Anything that doesn't fit waits safely in the player's data until there's room.
        List<ItemStack> stillWaiting = new ArrayList<>();
        for (ItemStack w : waiting) {
            if (isEmpty(w)) continue;
            stillWaiting.addAll(inv.addItem(w).values());
        }
        if (stillWaiting.isEmpty()) {
            pdc.remove(pendingKey);
        } else {
            pdc.set(pendingKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(stillWaiting));
            owner.sendMessage(prefix() + ChatColor.YELLOW + stillWaiting.size()
                    + " stack(s) are waiting for room in your ender chest. Take something out and reopen it.");
        }
        holder.inventory = inv;
        holder.lastVanilla = owner.getEnderChest().getContents().clone();
        cloneAll(holder.lastVanilla);
        // Write it out straight away: waiting items or an import just moved into the chest must be
        // saved even if the chest then doesn't open (another plugin cancels it).
        save(holder);
        return inv;
    }

    private void save(Session session) {
        Inventory inv = session.inventory;
        Player owner = session.ownerPlayer;
        // Something else changed the vanilla ender chest while this was open (/item, another plugin):
        // take that change instead of overwriting it.
        ItemStack[] vanillaNow = owner.getEnderChest().getContents();
        for (int i = 0; i < VANILLA_SIZE && i < inv.getSize() && i < vanillaNow.length; i++) {
            if (!same(vanillaNow[i], session.lastVanilla[i])) inv.setItem(i, vanillaNow[i]);
        }
        ItemStack[] all = inv.getContents();
        ItemStack[] vanillaPart = new ItemStack[VANILLA_SIZE];
        System.arraycopy(all, 0, vanillaPart, 0, Math.min(VANILLA_SIZE, all.length));
        owner.getEnderChest().setContents(vanillaPart);
        session.lastVanilla = vanillaPart.clone();
        cloneAll(session.lastVanilla);

        ItemStack[] extra = new ItemStack[Math.max(0, all.length - VANILLA_SIZE)];
        if (extra.length > 0) System.arraycopy(all, VANILLA_SIZE, extra, 0, extra.length);
        boolean empty = true;
        for (ItemStack i : extra) if (!isEmpty(i)) empty = false;
        PersistentDataContainer pdc = owner.getPersistentDataContainer();
        if (empty) pdc.remove(extraKey);
        else pdc.set(extraKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(extra));
    }

    /** Save a tick after any change, so the player's data always matches what's in the chest. */
    private void saveSoon(Session session) {
        if (!pendingSaves.add(session.owner)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingSaves.remove(session.owner);
            if (sessions.get(session.owner) == session) save(session);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSessionClick(InventoryClickEvent event) {
        if (Holders.of(event.getView().getTopInventory()) instanceof Session session) saveSoon(session);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSessionDrag(InventoryDragEvent event) {
        if (Holders.of(event.getView().getTopInventory()) instanceof Session session) saveSoon(session);
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (isEmpty(a) && isEmpty(b)) return true;
        return Objects.equals(a, b);
    }

    private static void cloneAll(ItemStack[] items) {
        for (int i = 0; i < items.length; i++) if (items[i] != null) items[i] = items[i].clone();
    }

    // ------------------------------------------------------------------ BetterEnderChest import

    /**
     * If this player has a BetterEnderChest chest that hasn't been imported yet, returns its items
     * by slot. Items that can't be read are skipped (and logged), never the whole chest.
     */
    private Map<Integer, ItemStack> importBetterEnderChest(Player owner) {
        PersistentDataContainer pdc = owner.getPersistentDataContainer();
        if (pdc.has(importedKey, PersistentDataType.BYTE) || importTriedThisRun.contains(owner.getUniqueId())) return null;
        File data = new File(plugin.getDataFolder().getParentFile(), "BetterEnderChest/chestData");
        File file = new File(data, owner.getUniqueId() + ".dat");
        if (!file.isFile()) file = new File(data, owner.getName().toLowerCase(Locale.ROOT) + ".dat");
        if (!file.isFile()) {
            pdc.set(importedKey, PersistentDataType.BYTE, (byte) 1); // nothing to bring over, never look again
            return null;
        }
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        int skipped = 0;
        try (InputStream in = new FileInputStream(file)) {
            Map<String, Nbt.Tag> root = Nbt.readCompressed(in).compound();
            int dataVersion = root.containsKey("DataVersion") ? root.get("DataVersion").asInt() : 0;
            Nbt.Tag list = root.get("Inventory");
            if (list != null) {
                for (Nbt.Tag entry : Nbt.items(list)) {
                    try {
                        Map<String, Nbt.Tag> item = new LinkedHashMap<>(entry.compound());
                        Nbt.Tag slot = item.remove("Slot");
                        if (slot == null) continue;
                        if (dataVersion > 0) item.put("DataVersion", new Nbt.Tag(Nbt.INT, dataVersion));
                        ItemStack stack = ItemStack.deserializeBytes(Nbt.writeCompressed(item));
                        if (stack.getType().isAir()) continue;
                        int index = slot.asInt() & 0xFF;
                        // Two items in one slot (shouldn't happen): keep both; the second waits for room.
                        while (items.containsKey(index)) index += 100;
                        items.put(index, stack);
                    } catch (IOException | RuntimeException bad) {
                        skipped++;
                        plugin.getLogger().log(Level.WARNING, "Skipped an unreadable item in " + owner.getName()
                                + "'s BetterEnderChest", bad);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            importTriedThisRun.add(owner.getUniqueId());
            pdc.set(importFailedKey, PersistentDataType.BYTE, (byte) 1);
            plugin.getLogger().log(Level.SEVERE, "Could not import " + owner.getName() + "'s BetterEnderChest ("
                    + file + "). Their vanilla ender chest is shown instead; the old file is untouched.", e);
            owner.sendMessage(prefix() + ChatColor.RED + "Your old ender chest couldn't be moved over. Tell an admin.");
            return null;
        }
        pdc.set(importedKey, PersistentDataType.BYTE, (byte) 1);
        pdc.remove(importFailedKey);
        plugin.getLogger().info("Imported " + items.size() + " item stack(s) from " + owner.getName() + "'s BetterEnderChest"
                + (skipped > 0 ? " (" + skipped + " unreadable item(s) skipped, see above)." : "."));
        return items;
    }

    // ------------------------------------------------------------------ /enderchest

    private final class EnderChestCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            Player owner = player;
            if (args.length >= 1) {
                if (!player.hasPermission("survivalcore.enderchest.others")) {
                    player.sendMessage(prefix() + ChatColor.RED + "You can only open your own ender chest.");
                    return true;
                }
                owner = Bukkit.getPlayerExact(args[0]);
                if (owner == null) {
                    player.sendMessage(prefix() + ChatColor.RED + args[0] + " isn't online.");
                    return true;
                }
            }
            open(player, owner, null);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> names = new ArrayList<>();
            if (args.length == 1 && sender.hasPermission("survivalcore.enderchest.others")) {
                Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
                names.removeIf(n -> !n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)));
            }
            return names;
        }
    }
}

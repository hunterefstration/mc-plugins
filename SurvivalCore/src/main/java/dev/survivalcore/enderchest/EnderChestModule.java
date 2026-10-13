package dev.survivalcore.enderchest;

import dev.survivalcore.util.Holders;
import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.Nbt;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lidded;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bigger ender chests (up to 6 rows). The first 3 rows are the player's normal vanilla ender
 * chest, so nothing is lost if this is ever turned off; the extra rows are saved in
 * plugins/SurvivalCore/enderchests/. Players coming from BetterEnderChest get their chest
 * imported automatically the first time they open it.
 */
public final class EnderChestModule extends Module {

    private static final int VANILLA_SIZE = 27;

    private final File folder;
    private final File importedFile;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<Location, Integer> openLids = new HashMap<>();
    private Set<String> imported = new HashSet<>();
    private Set<String> failed = new HashSet<>();

    /** One open ender chest, shared by everyone looking at it. */
    private final class Session implements InventoryHolder {
        final UUID owner;
        final Player ownerPlayer;
        Inventory inventory;
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
        this.folder = new File(plugin.getDataFolder(), "enderchests");
        this.importedFile = new File(folder, "imported-from-betterenderchest.yml");
        plugin.bind("enderchest", this, new EnderChestCommand());
    }

    @Override
    public void enable() {
        folder.mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(importedFile);
        imported = new HashSet<>(yaml.getStringList("players"));
        failed = new HashSet<>(yaml.getStringList("failed"));
    }

    @Override
    public void disable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            for (HumanEntity viewer : new ArrayList<>(s.inventory.getViewers())) viewer.closeInventory();
            save(s);
        }
        sessions.clear();
        for (Location lid : openLids.keySet()) setLid(lid, false);
        openLids.clear();
        viewerChests.clear();
        pendingSaves.clear();
    }

    private int rowsFor(Player player) {
        int rows = config().getInt("rows", 6);
        for (int n = 6; n > rows; n--) {
            if (player != null && player.hasPermission("survivalcore.enderchest.rows." + n)) {
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
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        event.setCancelled(true);
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (block.getRelative(BlockFace.UP).getType().isOccluding()) return; // vanilla: blocked lid
        if (open(player, player, block.getLocation())) player.incrementStatistic(Statistic.ENDERCHEST_OPENED);
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
            if (session.viewers.isEmpty()) sessions.remove(owner.getUniqueId());
            return false;
        }
        session.viewers.add(viewer.getUniqueId());
        if (chest != null) {
            openLids.merge(chest, 1, Integer::sum);
            viewerChests.put(viewer.getUniqueId(), chest);
            setLid(chest, true);
        }
        return true;
    }

    private final Map<UUID, Location> viewerChests = new HashMap<>();

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
            }
        }
        save(session);
        if (session.viewers.isEmpty()) sessions.remove(session.owner);
    }

    /** Saves while the owner is still online (an admin might be looking at their chest). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        for (HumanEntity viewer : new ArrayList<>(session.inventory.getViewers())) viewer.closeInventory();
        save(session);
        sessions.remove(session.owner);
    }

    /** Save a tick after any change, so a crash can't dupe or lose what was moved. */
    private final Set<UUID> pendingSaves = new HashSet<>();

    private void saveSoon(Session session) {
        if (!pendingSaves.add(session.owner)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingSaves.remove(session.owner);
            if (sessions.get(session.owner) == session) save(session);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSessionClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (Holders.of(event.getView().getTopInventory()) instanceof Session session) saveSoon(session);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSessionDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (Holders.of(event.getView().getTopInventory()) instanceof Session session) saveSoon(session);
    }

    private static void setLid(Location at, boolean open) {
        if (at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        if (!(at.getBlock().getState() instanceof Lidded lidded)) return;
        if (open) lidded.open();
        else lidded.close();
    }

    // ------------------------------------------------------------------ storage

    private File extraFile(UUID owner) {
        return new File(folder, owner + ".dat");
    }

    /** Builds the big inventory: vanilla ender chest first, then the extra rows. */
    private Inventory load(Player owner, Session holder) {
        List<ItemStack> items = new ArrayList<>();
        ItemStack[] vanilla = owner.getEnderChest().getContents();
        for (ItemStack i : vanilla) items.add(i);
        try {
            File extra = extraFile(owner.getUniqueId());
            if (extra.isFile()) {
                for (ItemStack i : ItemStack.deserializeItemsFromBytes(Files.readAllBytes(extra.toPath()))) items.add(i);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the extra ender chest rows of " + owner.getName()
                    + "; not opening it so nothing gets overwritten.", e);
            owner.sendMessage(prefix() + ChatColor.RED + "Your ender chest couldn't be loaded. Tell an admin.");
            return null;
        }

        boolean failedBefore = failed.contains(owner.getUniqueId().toString());
        Map<Integer, ItemStack> fromBec = importBetterEnderChest(owner);
        if (fromBec != null && !failedBefore) {
            // BetterEnderChest's chest replaces the vanilla one, which it had left stale.
            items.clear();
            int max = fromBec.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            for (int i = 0; i <= max; i++) items.add(fromBec.get(i));
        } else if (fromBec != null) {
            // An earlier import failed and they've been using this chest since: keep it, add the old items.
            items.addAll(fromBec.values());
        }

        int rows = rowsFor(owner);
        // Never hide items: grow to fit whatever is stored.
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) != null && !items.get(i).getType().isAir()) {
                rows = Math.max(rows, Math.min(6, i / 9 + 1));
                break;
            }
        }
        Inventory inv = Bukkit.createInventory(holder, rows * 9, config().getString("title", "Ender Chest"));
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item == null || item.getType().isAir()) continue;
            if (i < inv.getSize()) inv.setItem(i, item);
            else overflow.add(item);
        }
        if (!overflow.isEmpty()) {
            for (ItemStack rest : inv.addItem(overflow.toArray(new ItemStack[0])).values()) {
                owner.getWorld().dropItem(owner.getLocation(), rest);
            }
        }
        if (fromBec != null) {
            holder.inventory = inv;
            save(holder); // write the imported items out straight away
        }
        return inv;
    }

    private void save(Session session) {
        Inventory inv = session.inventory;
        ItemStack[] all = inv.getContents();
        ItemStack[] vanillaPart = new ItemStack[VANILLA_SIZE];
        System.arraycopy(all, 0, vanillaPart, 0, Math.min(VANILLA_SIZE, all.length));
        session.ownerPlayer.getEnderChest().setContents(vanillaPart);
        ItemStack[] extra = new ItemStack[Math.max(0, all.length - VANILLA_SIZE)];
        if (extra.length > 0) System.arraycopy(all, VANILLA_SIZE, extra, 0, extra.length);
        File file = extraFile(session.owner);
        try {
            boolean empty = true;
            for (ItemStack i : extra) if (i != null && !i.getType().isAir()) empty = false;
            if (empty) {
                Files.deleteIfExists(file.toPath());
            } else {
                File tmp = new File(folder, session.owner + ".dat.tmp");
                Files.write(tmp.toPath(), ItemStack.serializeItemsAsBytes(extra));
                Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save the extra ender chest rows of " + session.owner, e);
        }
    }

    // ------------------------------------------------------------------ BetterEnderChest import

    /**
     * If this player has a BetterEnderChest chest that hasn't been imported yet, returns its items
     * by slot (that chest replaces the vanilla one, which BetterEnderChest left stale). Returns
     * null when there's nothing to import.
     */
    private Map<Integer, ItemStack> importBetterEnderChest(Player owner) {
        String id = owner.getUniqueId().toString();
        if (imported.contains(id)) return null;
        File data = new File(plugin.getDataFolder().getParentFile(), "BetterEnderChest/chestData");
        File file = new File(data, id + ".dat");
        if (!file.isFile()) file = new File(data, owner.getName().toLowerCase(Locale.ROOT) + ".dat");
        if (!file.isFile()) {
            markImported(id); // nothing to bring over, never look again
            return null;
        }
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(file)) {
            Nbt.Tag root = Nbt.readCompressed(in);
            Map<String, Nbt.Tag> r = root.compound();
            int dataVersion = r.containsKey("DataVersion") ? r.get("DataVersion").asInt() : 0;
            Nbt.Tag list = r.get("Inventory");
            if (list != null) {
                for (Nbt.Tag entry : Nbt.items(list)) {
                    Map<String, Nbt.Tag> item = new LinkedHashMap<>(entry.compound());
                    Nbt.Tag slot = item.remove("Slot");
                    if (slot == null) continue;
                    if (dataVersion > 0) item.put("DataVersion", new Nbt.Tag(Nbt.INT, dataVersion));
                    ItemStack stack = ItemStack.deserializeBytes(Nbt.writeCompressed(item));
                    int index = slot.asInt() & 0xFF;
                    if (!stack.getType().isAir()) items.put(index, stack);
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not import " + owner.getName() + "'s BetterEnderChest ("
                    + file + "). Their vanilla ender chest is shown instead; the old file is untouched.", e);
            owner.sendMessage(prefix() + ChatColor.RED + "Your old ender chest couldn't be moved over. Tell an admin.");
            failed.add(id);
            writeImported();
            return null;
        }
        markImported(id);
        plugin.getLogger().info("Imported " + items.size() + " item stack(s) from " + owner.getName() + "'s BetterEnderChest.");
        return items;
    }

    private void markImported(String id) {
        imported.add(id);
        writeImported();
    }

    private void writeImported() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("players", new ArrayList<>(imported));
        yaml.set("failed", new ArrayList<>(failed));
        try {
            yaml.save(importedFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + importedFile.getName(), e);
        }
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

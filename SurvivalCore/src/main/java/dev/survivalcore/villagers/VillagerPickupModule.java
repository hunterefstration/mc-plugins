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
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ticketFile);
        var section = yaml.getConfigurationSection("tickets");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    tickets.put(UUID.fromString(key), UUID.fromString(section.getString(key, "")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveTickets() {
        YamlConfiguration yaml = new YamlConfiguration();
        tickets.forEach((t, e) -> yaml.set("tickets." + t, e.toString()));
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
        saveTickets();
        ItemStack item = createItem(target, data, ticket);
        target.remove();
        player.getInventory().addItem(item);
        // Save both sides now, so a crash can't leave the villager in the world *and* in the item.
        player.saveData();
        target.getWorld().save();
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
            // Same villager, same UUID - unless one with that UUID somehow exists already.
            if (Bukkit.getEntity(entity.getUniqueId()) != null) {
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
        saveTickets();
        player.saveData();
        target.getWorld().save();
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
     * After a crash, a picked-up villager whose removal never got saved would load again while
     * its item also exists; the item is the real one, so the leftover goes.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (tickets.isEmpty()) return;
        Set<UUID> pickedUp = new HashSet<>(tickets.values());
        for (Entity e : event.getEntities()) {
            if (pickedUp.contains(e.getUniqueId()) && allowed.contains(e.getType())) e.remove();
        }
    }
}

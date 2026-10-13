package dev.survivalcore.villagers;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.Names;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Merchant;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Sneak + right-click a villager to pick it up as an item; right-click a block with that item to
 * put it back down. Everything about the villager is kept (trades, level, XP, profession, name,
 * gossip/discounts), because the whole entity is saved into the item.
 */
public final class VillagerPickupModule extends Module {

    private final NamespacedKey dataKey;
    private final NamespacedKey typeKey;
    private final Set<EntityType> allowed = new HashSet<>();
    private boolean requireSneak;
    private boolean allowBabies;

    public VillagerPickupModule(SurvivalCore plugin) {
        super(plugin, "villager-pickup", "VillagerPickup");
        this.dataKey = new NamespacedKey(plugin, "picked_entity");
        this.typeKey = new NamespacedKey(plugin, "picked_type");
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
    }

    // ------------------------------------------------------------------ picking up

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Entity target = event.getRightClicked();

        // Never let our item act as a normal spawn egg on a mob.
        if (isPickedUp(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }

        if (!allowed.contains(target.getType()) || !(target instanceof LivingEntity living)) return;
        if (requireSneak && !player.isSneaking()) return;
        if (!player.getInventory().getItemInMainHand().getType().isAir()) return; // empty hand only
        if (!player.hasPermission("survivalcore.villagerpickup")) return;
        if (living.isDead() || !living.isValid()) return;
        if (!allowBabies && target instanceof org.bukkit.entity.Ageable a && !a.isAdult()) return;
        if (target instanceof Merchant merchant && merchant.isTrading()) {
            player.sendMessage(prefix() + ChatColor.RED + "Someone is trading with that villager.");
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        if (!target.getPassengers().isEmpty()) target.eject();
        if (target.isInsideVehicle()) target.leaveVehicle();
        if (living.isLeashed()) living.setLeashHolder(null);

        byte[] data;
        try {
            data = Bukkit.getUnsafe().serializeEntity(target);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not pick up " + target.getType(), t);
            player.sendMessage(prefix() + ChatColor.RED + "Couldn't pick that up.");
            return;
        }
        ItemStack item = createItem(target, data);
        target.remove();

        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) player.getWorld().dropItem(player.getLocation(), rest);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
    }

    private ItemStack createItem(Entity entity, byte[] data) {
        Material egg = Material.matchMaterial(entity.getType().name() + "_SPAWN_EGG");
        ItemStack item = new ItemStack(egg != null ? egg : Material.VILLAGER_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();

        String typeName = Names.pretty(entity.getType().name());
        List<String> lore = new ArrayList<>();
        String title = typeName;
        if (entity instanceof Villager v) {
            String profession = Names.pretty(v.getProfession().getKey().getKey());
            title = v.getProfession() == Villager.Profession.NONE || v.getProfession() == Villager.Profession.NITWIT
                    ? profession + " Villager" : profession;
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
        // Whatever happens, it must never behave like a normal spawn egg (e.g. changing a spawner).
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() == null) return;

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        Block target = clicked.isPassable() && !clicked.isLiquid() ? clicked : clicked.getRelative(event.getBlockFace());
        if (!target.isPassable() || !target.getRelative(0, 1, 0).isPassable()) {
            player.sendMessage(prefix() + ChatColor.RED + "There isn't enough room there.");
            return;
        }
        Location at = target.getLocation().add(0.5, 0, 0.5);
        at.setYaw(player.getLocation().getYaw() + 180);

        byte[] data = item.getItemMeta().getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        Entity entity;
        try {
            // Keep the villager's own UUID (it's the same villager), unless one with that UUID somehow exists.
            entity = Bukkit.getUnsafe().deserializeEntity(data, target.getWorld(), true);
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
        if (event.getHand() == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
        else player.getInventory().setItemInMainHand(null);
        player.playSound(at, Sound.ENTITY_VILLAGER_AMBIENT, 0.8f, 1f);
    }

    /** Dispensers would spawn a brand-new villager from it (a free villager), so block that. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isPickedUp(event.getItem())) event.setCancelled(true);
    }
}

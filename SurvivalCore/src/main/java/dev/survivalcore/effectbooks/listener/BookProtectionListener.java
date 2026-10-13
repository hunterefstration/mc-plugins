package dev.survivalcore.effectbooks.listener;

import dev.survivalcore.effectbooks.BookManager;
import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.gui.Menu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Keeps effect books with their holder. A book can't be dropped, stored, framed, put on a lectern,
 * bundled, or lost on death, so lava, fire, cacti, explosions, the void and despawning can never
 * reach it. Only an admin deleting it (via /eb) gets rid of it.
 */
public final class BookProtectionListener implements Listener {

    private final EffectBooksModule plugin;
    private final BookManager manager;

    public BookProtectionListener(EffectBooksModule plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    private boolean isBook(ItemStack item) {
        return manager.isEffectBook(item);
    }

    private void deny(Player player) {
        player.sendMessage(plugin.prefix() + ChatColor.RED + "Your effect book can't leave you.");
    }

    // ------------------------------------------------------------------ dropping / dying

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isBook(event.getItemDrop().getItemStack())) {
            // Dropping it just puts it away again.
            event.setCancelled(true);
            Player player = event.getPlayer();
            plugin.getServer().getScheduler().runTask(plugin.core(), () -> manager.putAway(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // Never dropped on death; it is handed back on respawn.
        event.getDrops().removeIf(this::isBook);
    }

    // ------------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof Menu) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        ItemStack swapped = null;
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            swapped = player.getInventory().getItem(event.getHotbarButton());
        } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
            swapped = player.getInventory().getItemInOffHand();
        }

        boolean cursorBook = isBook(cursor);
        boolean currentBook = isBook(current);
        boolean swappedBook = isBook(swapped);
        if (!cursorBook && !currentBook && !swappedBook) return;

        Inventory clicked = event.getClickedInventory();
        boolean clickedOwn = clicked instanceof PlayerInventory && clicked.equals(player.getInventory());
        InventoryAction action = event.getAction();

        boolean block =
                // clicking outside the window / drop keys
                clicked == null
                || action.name().startsWith("DROP")
                // creative middle-click would duplicate it
                || action == InventoryAction.CLONE_STACK
                // placing or swapping it into a chest, crafting grid, anvil, another player's inventory...
                || (!clickedOwn && (cursorBook || swappedBook))
                // shift-clicking it into whatever container is open
                || (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && currentBook && clickedOwn
                        && event.getView().getTopInventory().getType() != InventoryType.CRAFTING)
                // stuffing it into a bundle (either direction)
                || (cursorBook && isBundle(current))
                || (currentBook && isBundle(cursor));

        if (block) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!isBook(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) deny(p);
                return;
            }
        }
    }

    /** Clean up any stray copy found in a container (e.g. left over from before protections). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top instanceof PlayerInventory || top.getHolder() instanceof Menu) return;
        boolean removed = false;
        for (int slot = 0; slot < top.getSize(); slot++) {
            if (isBook(top.getItem(slot))) {
                top.setItem(slot, null);
                removed = true;
            }
        }
        if (removed) manager.queueRefresh();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (isBook(event.getItem().getItemStack())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ world interactions

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!isBook(event.getItem())) return;
        Block block = event.getClickedBlock();
        Material type = block.getType();
        if (type == Material.LECTERN || type == Material.CHISELED_BOOKSHELF || type == Material.DECORATED_POT
                || type.name().endsWith("_SHELF")) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame) && !(event.getRightClicked() instanceof Allay)) return;
        ItemStack hand = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (isBook(hand)) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (isBook(event.getPlayerItem())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    // ------------------------------------------------------------------ item entities (belt and braces)

    /** A book should never exist on the ground; if something tries, remove it and give it back to the holder. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (isBook(event.getEntity().getItemStack())) {
            event.setCancelled(true);
            manager.queueRefresh();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && isBook(item.getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemBurn(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item && isBook(item.getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (isBook(event.getEntity().getItemStack())) event.setCancelled(true);
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }
}

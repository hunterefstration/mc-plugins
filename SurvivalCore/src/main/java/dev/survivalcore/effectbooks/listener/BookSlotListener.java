package dev.survivalcore.effectbooks.listener;

import dev.survivalcore.effectbooks.BookManager;
import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.gui.BookPickerMenu;
import dev.survivalcore.effectbooks.model.Book;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The holder's book lives in the output slot of the 2x2 crafting grid in their own inventory. It
 * only shows there while the grid is empty, so it never gets in the way of crafting, and it
 * doesn't use up a real inventory slot. Clicking it puts the book in their hand to write in;
 * switching away from it (or pressing Done) puts it back.
 */
public final class BookSlotListener implements Listener {

    private final EffectBooksModule plugin;
    private final BookManager manager;

    public BookSlotListener(EffectBooksModule plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    /** Vanilla recalculates the output whenever the grid changes; show the book again once it's empty. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        if (inv.getType() != InventoryType.CRAFTING) return;
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        for (ItemStack item : inv.getMatrix()) {
            if (item != null && !item.getType().isAir()) return;
        }
        List<Book> held = manager.booksHeldBy(player);
        if (!held.isEmpty()) inv.setResult(manager.displayItem(held));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickPreview(InventoryClickEvent event) {
        if (event.getView().getTopInventory() == null
                || event.getView().getTopInventory().getType() != InventoryType.CRAFTING) return;
        if (event.getRawSlot() != 0 || !manager.isDisplay(event.getCurrentItem())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Changing inventories inside a click event is unsafe; act on the next tick.
        plugin.getServer().getScheduler().runTask(plugin.core(), () -> open(player));
    }

    /** Puts the holder's book in their hand, or lets them pick one if they hold several. */
    public static void open(EffectBooksModule plugin, Player player) {
        List<Book> held = plugin.manager().booksHeldBy(player);
        if (held.isEmpty()) {
            player.sendMessage(plugin.prefix() + ChatColor.GRAY + "You don't hold an effect book.");
        } else if (held.size() == 1) {
            takeOut(plugin, player, held.get(0));
        } else {
            new BookPickerMenu(plugin, held).open(player);
        }
    }

    private void open(Player player) {
        open(plugin, player);
    }

    public static void takeOut(EffectBooksModule plugin, Player player, Book book) {
        if (!plugin.manager().giveToHand(player, book)) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Free up an inventory slot to write in your book.");
            return;
        }
        player.closeInventory();
        player.sendMessage(plugin.prefix() + ChatColor.GRAY + "Right-click to write names in your "
                + ChatColor.LIGHT_PURPLE + book.displayName() + ChatColor.GRAY
                + ". It goes back when you press Done or switch items.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        // Closing the inventory clears the output slot; put the preview back afterwards.
        plugin.getServer().getScheduler().runTask(plugin.core(), () -> manager.syncBooks(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (manager.bookId(player.getInventory().getItem(event.getPreviousSlot())) == null) return;
        plugin.getServer().getScheduler().runTask(plugin.core(), () -> manager.syncBooks(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (manager.isEffectBook(event.getMainHandItem()) || manager.isEffectBook(event.getOffHandItem())) {
            event.setCancelled(true);
            manager.putAway(event.getPlayer());
        }
    }
}

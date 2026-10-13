package dev.survivalcore.effectbooks.listener;

import dev.survivalcore.effectbooks.BookManager;
import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.model.Book;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;
import java.util.UUID;

/** Shows books on join/respawn and turns book edits into target changes. */
public final class PlayerListener implements Listener {

    private final EffectBooksModule plugin;
    private final BookManager manager;

    public PlayerListener(EffectBooksModule plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin.core(), () -> manager.refresh(player), 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin.core(), () -> manager.refresh(player), 1L);
    }

    /** Names that weren't valid usernames in each player's last edit (for the message at MONITOR). */
    private final java.util.Map<UUID, java.util.List<String>> invalidNames = new java.util.HashMap<>();
    /** How many names didn't fit (over the 1300-name limit) in each player's last edit. */
    private final java.util.Map<UUID, Integer> droppedNames = new java.util.HashMap<>();

    /** Checks the edit and rewrites the pages cleanly. Nothing is saved until MONITOR. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEdit(PlayerEditBookEvent event) {
        UUID id = manager.bookId(event.getPreviousBookMeta());
        if (id == null) return;
        Player player = event.getPlayer();
        Book book = manager.get(id);

        if (book == null || !book.isHolder(player.getName())) {
            event.setCancelled(true);
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Only the holder of this book can write in it.");
            manager.queueRefresh();
            return;
        }
        if (manager.isBroken() || !manager.canWrite()) {
            event.setCancelled(true);
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Effect books are being fixed by an admin. Try again soon.");
            return;
        }

        BookManager.ParsedPages parsed = BookManager.parsePages(event.getNewBookMeta());
        if (event.isSigning()) {
            event.setSigning(false);
            player.sendMessage(plugin.prefix() + ChatColor.GRAY + "Effect books can't be signed. Your names were still saved.");
        }
        List<String> names = manager.mergeEdit(player, book, parsed.names());
        invalidNames.put(player.getUniqueId(), parsed.invalid());
        droppedNames.put(player.getUniqueId(), Math.max(0, names.size() - BookManager.MAX_TARGETS));
        if (names.size() > BookManager.MAX_TARGETS) names = names.subList(0, BookManager.MAX_TARGETS);
        event.setNewBookMeta((BookMeta) manager.createItem(book, names).getItemMeta());
    }

    /** The edit really happened (no other plugin cancelled it): save the names. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditDone(PlayerEditBookEvent event) {
        UUID id = manager.bookId(event.getPreviousBookMeta());
        if (id == null) return;
        Player player = event.getPlayer();
        Book book = manager.get(id);
        List<String> invalid = invalidNames.remove(player.getUniqueId());
        Integer droppedCount = droppedNames.remove(player.getUniqueId());
        int dropped = droppedCount == null ? 0 : droppedCount;
        if (book == null || !book.isHolder(player.getName()) || manager.isBroken()) return;

        List<String> names = BookManager.parsePages(event.getNewBookMeta()).names();
        try {
            manager.setTargets(book, names);
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Effect books are being fixed by an admin. Try again soon.");
            manager.queueRefresh();
            return;
        }
        manager.finishEdit(player, book);
        // Done writing: the book goes back to its spot in the crafting slot.
        plugin.getServer().getScheduler().runTaskLater(plugin.core(), () -> manager.putAway(player), 1L);

        player.sendMessage(plugin.prefix() + ChatColor.LIGHT_PURPLE + book.displayName() + ChatColor.GRAY
                + " now grants " + ChatColor.AQUA + book.targets().size() + ChatColor.GRAY
                + (book.targets().size() == 1 ? " player." : " players."));
        if (dropped > 0) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "A book holds at most " + BookManager.MAX_TARGETS
                    + " names; the last " + dropped + " weren't added.");
        }
        if (invalid != null && !invalid.isEmpty()) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Ignored invalid names: " + String.join(", ", invalid));
        }
    }
}

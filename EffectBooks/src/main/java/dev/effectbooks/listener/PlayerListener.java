package dev.effectbooks.listener;

import dev.effectbooks.BookManager;
import dev.effectbooks.EffectBooksPlugin;
import dev.effectbooks.model.Book;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.meta.BookMeta;

import java.util.UUID;

/** Gives books on join/respawn and turns book edits into target changes. */
public final class PlayerListener implements Listener {

    private final EffectBooksPlugin plugin;
    private final BookManager manager;

    public PlayerListener(EffectBooksPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.manager();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> manager.refresh(player), 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> manager.refresh(player), 1L);
    }

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

        BookManager.ParsedPages parsed = BookManager.parsePages(event.getNewBookMeta());
        if (event.isSigning()) {
            event.setSigning(false);
            player.sendMessage(plugin.prefix() + ChatColor.GRAY + "Effect books can't be signed. Your names were still saved.");
        }

        manager.setTargets(book, parsed.names());
        event.setNewBookMeta((BookMeta) manager.createItem(book).getItemMeta());

        player.sendMessage(plugin.prefix() + ChatColor.LIGHT_PURPLE + book.displayName() + ChatColor.GRAY
                + " now grants " + ChatColor.AQUA + parsed.names().size() + ChatColor.GRAY
                + (parsed.names().size() == 1 ? " player." : " players."));
        if (!parsed.invalid().isEmpty()) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "Ignored invalid names: "
                    + String.join(", ", parsed.invalid()));
        }
    }
}

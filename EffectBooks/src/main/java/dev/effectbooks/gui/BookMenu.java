package dev.effectbooks.gui;

import dev.effectbooks.EffectBooksPlugin;
import dev.effectbooks.model.Book;
import dev.effectbooks.util.Effects;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

/** Manage one book: reassign, change effect/level, edit names, delete. */
public final class BookMenu extends Menu {

    private static final int GUI_MAX_LEVEL = 10;
    private final UUID bookId;

    public BookMenu(EffectBooksPlugin plugin, UUID bookId) {
        super(plugin, 3, "Manage Book");
        this.bookId = bookId;
    }

    @Override
    public void open(Player player) {
        if (plugin.manager().get(bookId) == null) {
            player.sendMessage(plugin.prefix() + ChatColor.RED + "That book no longer exists.");
            new MainMenu(plugin, 0).open(player);
            return;
        }
        super.open(player);
    }

    @Override
    protected void draw() {
        Book book = plugin.manager().get(bookId);
        if (book == null) return;
        for (int slot = 0; slot < 27; slot++) set(slot, Icons.filler(), null);

        set(4, MainMenu.bookIcon(book), null);

        set(10, Icons.icon(Material.NAME_TAG, ChatColor.GOLD + "Reassign holder",
                "Current: " + book.holder(),
                "Moves the book and all its names",
                "to another player."), this::reassign);

        set(11, Icons.effect(book.effect(), ChatColor.AQUA + "Change effect",
                "Current: " + Effects.prettyName(book.effect())), e -> {
            Player admin = (Player) e.getWhoClicked();
            new EffectPickerMenu(plugin, "New effect", effect -> {
                Book b = plugin.manager().get(bookId);
                if (b == null) { reopenMain(admin); return; }
                try {
                    plugin.manager().changeEffect(b, effect);
                    admin.sendMessage(plugin.prefix() + ChatColor.GREEN + "It is now a " + b.displayName() + ".");
                } catch (IllegalArgumentException ex) {
                    admin.sendMessage(plugin.prefix() + ChatColor.RED + ex.getMessage());
                }
                open(admin);
            }, () -> open(admin), 0).open(admin);
        });

        set(12, Icons.icon(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Level: " + Effects.roman(book.level()),
                "Left-click: +1",
                "Right-click: -1"), e -> {
            int level = book.level() + (e.isRightClick() ? -1 : 1);
            if (level < 1 || level > Math.max(GUI_MAX_LEVEL, book.level())) return;
            plugin.manager().setLevel(book, level);
            redraw();
        });

        set(14, Icons.icon(Material.PLAYER_HEAD, ChatColor.YELLOW + "Players in this book",
                book.targets().size() + " written",
                "View, add or remove names."),
                e -> new TargetsMenu(plugin, bookId, 0).open((Player) e.getWhoClicked()));

        set(16, Icons.icon(Material.LAVA_BUCKET, ChatColor.RED + "Delete book",
                "Removes the book from " + book.holder(),
                "and clears its effect from everyone."), e -> {
            Player admin = (Player) e.getWhoClicked();
            new ConfirmMenu(plugin, "Delete " + book.displayName() + "?", () -> {
                Book b = plugin.manager().get(bookId);
                if (b != null) {
                    plugin.manager().delete(b);
                    admin.sendMessage(plugin.prefix() + ChatColor.GREEN + "Deleted " + b.displayName()
                            + " (" + b.holder() + ").");
                }
                reopenMain(admin);
            }, () -> open(admin)).open(admin);
        });

        set(22, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Back"),
                e -> reopenMain((Player) e.getWhoClicked()));
    }

    private void reassign(InventoryClickEvent e) {
        Player admin = (Player) e.getWhoClicked();
        plugin.chatPrompt().ask(admin, "Who should hold this book now? (username)", name -> {
            Book b = plugin.manager().get(bookId);
            if (b == null) { reopenMain(admin); return; }
            try {
                String old = b.holder();
                plugin.manager().reassign(b, name);
                admin.sendMessage(plugin.prefix() + ChatColor.GREEN + b.displayName() + " moved from "
                        + old + " to " + name + ".");
            } catch (IllegalArgumentException ex) {
                admin.sendMessage(plugin.prefix() + ChatColor.RED + ex.getMessage());
            }
            open(admin);
        }, () -> open(admin));
    }

    private void reopenMain(Player admin) {
        new MainMenu(plugin, 0).open(admin);
    }
}

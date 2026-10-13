package dev.survivalcore.effectbooks.gui;

import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.model.Book;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The names written in a book. Click one to erase it. */
public final class TargetsMenu extends Menu {

    private static final int PER_PAGE = 45;
    private final UUID bookId;
    private final int page;

    public TargetsMenu(EffectBooksModule plugin, UUID bookId, int page) {
        super(plugin, 6, "Players in Book");
        this.bookId = bookId;
        this.page = page;
    }

    @Override
    protected void draw() {
        Book book = plugin.manager().get(bookId);
        if (book == null) return;
        List<String> targets = new ArrayList<>(book.targets());
        int pages = Math.max(1, (targets.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.min(this.page, pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= targets.size()) break;
            String name = targets.get(index);
            set(i, head(name), e -> {
                Book b = plugin.manager().get(bookId);
                if (b == null) return;
                plugin.manager().removeTarget(b, name);
                e.getWhoClicked().sendMessage(plugin.prefix() + ChatColor.GREEN + "Removed " + name
                        + " from " + b.displayName() + ".");
                redraw();
            });
        }

        for (int slot = 45; slot < 54; slot++) set(slot, Icons.filler(), null);
        if (page > 0) {
            set(45, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Previous page"),
                    e -> new TargetsMenu(plugin, bookId, page - 1).open((Player) e.getWhoClicked()));
        }
        if (page < pages - 1) {
            set(53, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Next page"),
                    e -> new TargetsMenu(plugin, bookId, page + 1).open((Player) e.getWhoClicked()));
        }
        set(48, Icons.icon(Material.EMERALD, ChatColor.GREEN + "Add a player",
                "Writes a name into the book."), e -> {
            Player admin = (Player) e.getWhoClicked();
            plugin.chatPrompt().ask(admin, "Which player should get " + book.displayName() + "'s effect?", name -> {
                Book b = plugin.manager().get(bookId);
                if (b == null) { new MainMenu(plugin, 0).open(admin); return; }
                try {
                    if (plugin.manager().addTarget(b, name)) {
                        admin.sendMessage(plugin.prefix() + ChatColor.GREEN + "Added " + name + ".");
                    } else {
                        admin.sendMessage(plugin.prefix() + ChatColor.GRAY + name + " is already in the book.");
                    }
                } catch (IllegalArgumentException ex) {
                    admin.sendMessage(plugin.prefix() + ChatColor.RED + ex.getMessage());
                }
                new TargetsMenu(plugin, bookId, page).open(admin);
            }, () -> new TargetsMenu(plugin, bookId, page).open(admin));
        });
        set(49, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Back"),
                e -> new BookMenu(plugin, bookId).open((Player) e.getWhoClicked()));
    }

    private static ItemStack head(String name) {
        ItemStack item = Icons.icon(Material.PLAYER_HEAD, ChatColor.WHITE + name,
                "", ChatColor.RED + "Click to remove");
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setOwningPlayer(online);
            item.setItemMeta(meta);
        }
        return item;
    }
}

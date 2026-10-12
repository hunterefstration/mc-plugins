package dev.effectbooks.gui;

import dev.effectbooks.EffectBooksPlugin;
import dev.effectbooks.model.Book;
import dev.effectbooks.util.Effects;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Lists every effect book. */
public final class MainMenu extends Menu {

    private static final int PER_PAGE = 45;
    private final int page;

    public MainMenu(EffectBooksPlugin plugin, int page) {
        super(plugin, 6, "Effect Books");
        this.page = page;
    }

    @Override
    protected void draw() {
        List<Book> books = plugin.manager().sortedBooks();
        int pages = Math.max(1, (books.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.min(this.page, pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= books.size()) break;
            Book book = books.get(index);
            set(i, bookIcon(book), e -> new BookMenu(plugin, book.id()).open((Player) e.getWhoClicked()));
        }

        for (int slot = 45; slot < 54; slot++) set(slot, Icons.filler(), null);
        if (page > 0) {
            set(45, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Previous page"),
                    e -> new MainMenu(plugin, page - 1).open((Player) e.getWhoClicked()));
        }
        if (page < pages - 1) {
            set(53, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Next page"),
                    e -> new MainMenu(plugin, page + 1).open((Player) e.getWhoClicked()));
        }
        set(48, Icons.icon(Material.BOOK, ChatColor.GOLD + "How it works",
                "Each book belongs to a holder.",
                "Names the holder writes in it",
                "(one per line) get the effect forever.",
                "",
                "Books: " + books.size() + "  -  Page " + (page + 1) + "/" + pages), null);
        set(49, Icons.icon(Material.EMERALD, ChatColor.GREEN + "Add a book",
                "Pick a holder and an effect."), e -> startAdd(plugin, (Player) e.getWhoClicked()));
        set(50, Icons.icon(Material.BARRIER, ChatColor.RED + "Close"), e -> e.getWhoClicked().closeInventory());
    }

    static ItemStack bookIcon(Book book) {
        ItemStack item = Icons.glow(Icons.icon(Material.WRITABLE_BOOK, ChatColor.LIGHT_PURPLE + book.displayName()));
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Holder: " + ChatColor.WHITE + book.holder());
        lore.add(ChatColor.GRAY + "Effect: " + ChatColor.AQUA + Effects.prettyName(book.effect())
                + " " + Effects.roman(book.level()));
        lore.add(ChatColor.GRAY + "Players written: " + ChatColor.WHITE + book.targets().size());
        int shown = 0;
        for (String t : book.targets()) {
            if (shown++ == 8) {
                lore.add(ChatColor.DARK_GRAY + "  ...and " + (book.targets().size() - 8) + " more");
                break;
            }
            lore.add(ChatColor.DARK_GRAY + "  - " + t);
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to manage");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static void startAdd(EffectBooksPlugin plugin, Player admin) {
        plugin.chatPrompt().ask(admin, "Who should hold the new book? (username)", name -> {
            if (!Effects.isValidName(name)) {
                admin.sendMessage(plugin.prefix() + ChatColor.RED + "'" + name + "' is not a valid username.");
                new MainMenu(plugin, 0).open(admin);
                return;
            }
            new EffectPickerMenu(plugin, "Effect for " + name + "'s book", effect -> {
                try {
                    Book book = plugin.manager().create(name, effect, 1);
                    admin.sendMessage(plugin.prefix() + ChatColor.GREEN + "Created " + book.displayName()
                            + " for " + name + ".");
                    new BookMenu(plugin, book.id()).open(admin);
                } catch (IllegalArgumentException ex) {
                    admin.sendMessage(plugin.prefix() + ChatColor.RED + ex.getMessage());
                    new MainMenu(plugin, 0).open(admin);
                }
            }, () -> new MainMenu(plugin, 0).open(admin), 0).open(admin);
        }, () -> new MainMenu(plugin, 0).open(admin));
    }
}

package dev.survivalcore.effectbooks.gui;

import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.listener.BookSlotListener;
import dev.survivalcore.effectbooks.model.Book;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** For holders with several books: pick which one to write in. */
public final class BookPickerMenu extends Menu {

    private final List<UUID> bookIds = new ArrayList<>();

    public BookPickerMenu(EffectBooksModule plugin, List<Book> books) {
        super(plugin, Math.max(1, Math.min(6, (books.size() + 8) / 9)), "Your Effect Books");
        for (Book b : books) bookIds.add(b.id());
    }

    @Override
    protected void draw() {
        int slot = 0;
        for (UUID id : bookIds) {
            if (slot >= getInventory().getSize()) break;
            Book book = plugin.manager().get(id);
            if (book == null) continue;
            set(slot++, plugin.manager().createItem(book), e -> {
                Player player = (Player) e.getWhoClicked();
                Book b = plugin.manager().get(id);
                if (b != null && b.isHolder(player.getName())) BookSlotListener.takeOut(plugin, player, b);
                else player.closeInventory();
            });
        }
    }
}

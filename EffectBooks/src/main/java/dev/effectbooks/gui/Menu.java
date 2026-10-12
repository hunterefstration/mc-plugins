package dev.effectbooks.gui;

import dev.effectbooks.EffectBooksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A chest GUI where every clickable slot has its own action. */
public abstract class Menu implements InventoryHolder {

    protected final EffectBooksPlugin plugin;
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(EffectBooksPlugin plugin, int rows, String title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** Fill the inventory. Called on open and on {@link #redraw()}. */
    protected abstract void draw();

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void redraw() {
        inventory.clear();
        actions.clear();
        draw();
    }

    public void open(Player player) {
        redraw();
        player.openInventory(inventory);
    }

    void handle(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) action.accept(event);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

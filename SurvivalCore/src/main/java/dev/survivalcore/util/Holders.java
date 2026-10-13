package dev.survivalcore.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class Holders {

    private Holders() {}

    /**
     * The inventory's holder without making a block-state snapshot (cheap enough for every click).
     * Falls back to the plain call where that isn't supported (e.g. test servers).
     */
    public static InventoryHolder of(Inventory inventory) {
        if (inventory == null) return null;
        try {
            return inventory.getHolder(false);
        } catch (RuntimeException e) { // e.g. "not implemented" on test servers
            return inventory.getHolder();
        }
    }
}

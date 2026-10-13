package dev.survivalcore.villagers;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Villagers never run out of stock. Trading doesn't use up a trade, anything already sold out is
 * restocked when the villager is opened, and while someone is trading every trade's limit is
 * raised so the screen never shows a trade as locked, however many times in a row it's used.
 * The real limits are put back when the trading screen closes, so the villager's data stays
 * vanilla.
 */
public final class InstantRestockModule extends Module {

    private static final int UNLIMITED = Integer.MAX_VALUE;
    private final NamespacedKey originalKey;

    public InstantRestockModule(SurvivalCore plugin) {
        super(plugin, "instant-restock", "InstantRestock");
        this.originalKey = new NamespacedKey(plugin, "restock_max_uses");
    }

    private boolean applies(AbstractVillager merchant) {
        return !(merchant instanceof WanderingTrader) || config().getBoolean("wandering-traders", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inv)) return;
        if (!(inv.getMerchant() instanceof AbstractVillager villager) || !applies(villager)) return;

        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        restoreLimits(villager); // in case the server stopped mid-trade last time
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        int[] originals = new int[recipes.size()];
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            originals[i] = recipe.getMaxUses();
            recipe.setUses(0);
            recipe.setMaxUses(UNLIMITED);
        }
        pdc.set(originalKey, PersistentDataType.INTEGER_ARRAY, originals);
        villager.setRecipes(recipes);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inv)) return;
        if (!(inv.getMerchant() instanceof AbstractVillager villager)) return;
        if (villager.isTrading() && villager.getTrader() != null && !villager.getTrader().equals(event.getPlayer())) return;
        restoreLimits(villager);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!applies(event.getVillager())) return;
        event.setIncreaseTradeUses(false);
    }

    /** Puts each trade's real limit back (trades unlocked at a level-up keep their own). */
    private void restoreLimits(AbstractVillager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        int[] originals = pdc.get(originalKey, PersistentDataType.INTEGER_ARRAY);
        if (originals == null) return;
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        for (int i = 0; i < recipes.size() && i < originals.length; i++) {
            MerchantRecipe recipe = recipes.get(i);
            if (recipe.getMaxUses() == UNLIMITED) recipe.setMaxUses(originals[i]);
            recipe.setUses(0);
        }
        villager.setRecipes(recipes);
        pdc.remove(originalKey);
    }

    @Override
    public void disable() {
        // Anyone still trading: close the screens so their villagers get their limits back.
        for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory() instanceof MerchantInventory inv
                    && inv.getMerchant() instanceof AbstractVillager v) {
                p.closeInventory();
                restoreLimits(v);
            }
        }
    }
}

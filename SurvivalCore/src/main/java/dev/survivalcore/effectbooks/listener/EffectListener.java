package dev.survivalcore.effectbooks.listener;

import dev.survivalcore.effectbooks.BookManager;
import dev.survivalcore.effectbooks.EffectBooksModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Stops milk, death, totems, /effect clear etc. from removing book effects. */
public final class EffectListener implements Listener {

    private final EffectBooksModule plugin;

    public EffectListener(EffectBooksModule plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        // PLUGIN is us removing an effect on purpose. EXPIRATION is a timed potion ending, or an
        // effect that ends itself (Absorption when its golden hearts are gone).
        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN) return;
        if (cause == EntityPotionEffectEvent.Cause.EXPIRATION) {
            PotionEffect old = event.getOldEffect();
            if (old != null && event.getModifiedType().equals(PotionEffectType.ABSORPTION)) {
                Integer amp = plugin.manager().desiredEffects(player.getName()).get(PotionEffectType.ABSORPTION);
                // Ours ran out of hearts, either on top or hidden under a stronger golden apple.
                if (amp != null && BookManager.hasOurs(old, amp)) plugin.manager().absorptionUsedUp(player);
            }
            return;
        }

        if (event.getModifiedType().isInstant()) return; // instant doses are meant to wear off
        Integer amplifier = plugin.manager().desiredEffects(player.getName()).get(event.getModifiedType());
        if (amplifier == null) return;

        PotionEffect old = event.getOldEffect();
        if (old == null || old.getAmplifier() <= amplifier) {
            event.setCancelled(true);
        } else {
            // A stronger temporary potion is being cleared; let it go and put ours back.
            plugin.getServer().getScheduler().runTask(plugin.core(), () -> plugin.manager().reconcileEffects(player));
        }
    }
}

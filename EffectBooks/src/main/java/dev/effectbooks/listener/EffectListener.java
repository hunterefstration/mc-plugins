package dev.effectbooks.listener;

import dev.effectbooks.EffectBooksPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffect;

/** Stops milk, death, totems, /effect clear etc. from removing book effects. */
public final class EffectListener implements Listener {

    private final EffectBooksPlugin plugin;

    public EffectListener(EffectBooksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        // PLUGIN is us removing an effect on purpose; EXPIRATION only happens to timed potions.
        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause == EntityPotionEffectEvent.Cause.PLUGIN || cause == EntityPotionEffectEvent.Cause.EXPIRATION) return;

        if (event.getModifiedType().isInstant()) return; // instant doses are meant to wear off
        Integer amplifier = plugin.manager().desiredEffects(player.getName()).get(event.getModifiedType());
        if (amplifier == null) return;

        PotionEffect old = event.getOldEffect();
        if (old == null || old.isInfinite() || old.getAmplifier() <= amplifier) {
            event.setCancelled(true);
        } else {
            // A stronger temporary potion is being cleared; let it go and put ours back.
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.manager().reconcileEffects(player));
        }
    }
}

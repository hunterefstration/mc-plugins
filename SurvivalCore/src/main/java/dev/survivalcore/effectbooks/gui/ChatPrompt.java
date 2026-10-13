package dev.survivalcore.effectbooks.gui;

import dev.survivalcore.effectbooks.EffectBooksModule;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Closes the menu and asks the player to type an answer in chat. */
public final class ChatPrompt implements Listener {

    private static final long TIMEOUT_TICKS = 20L * 60;

    private final EffectBooksModule plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Consumer<String> answer, Runnable onCancel, long token) {}

    public ChatPrompt(EffectBooksModule plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> answer, Runnable onCancel) {
        player.closeInventory();
        long token = System.nanoTime();
        pending.put(player.getUniqueId(), new Pending(answer, onCancel, token));
        player.sendMessage(plugin.prefix() + ChatColor.YELLOW + question);
        player.sendMessage(plugin.prefix() + ChatColor.GRAY + "Type it in chat, or type " + ChatColor.WHITE
                + "cancel" + ChatColor.GRAY + ".");
        plugin.getServer().getScheduler().runTaskLater(plugin.core(), () -> {
            Pending p = pending.get(player.getUniqueId());
            if (p != null && p.token == token) {
                pending.remove(player.getUniqueId());
                player.sendMessage(plugin.prefix() + ChatColor.GRAY + "Timed out.");
            }
        }, TIMEOUT_TICKS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent works on both Spigot and Paper
    public void onChat(AsyncPlayerChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        plugin.getServer().getScheduler().runTask(plugin.core(), () -> {
            if (message.equalsIgnoreCase("cancel")) {
                event.getPlayer().sendMessage(plugin.prefix() + ChatColor.GRAY + "Cancelled.");
                if (p.onCancel != null) p.onCancel.run();
            } else {
                p.answer.accept(message);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}

package dev.effectbooks;

import dev.effectbooks.command.EffectBooksCommand;
import dev.effectbooks.gui.ChatPrompt;
import dev.effectbooks.gui.MenuListener;
import dev.effectbooks.listener.BookProtectionListener;
import dev.effectbooks.listener.EffectListener;
import dev.effectbooks.listener.PlayerListener;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class EffectBooksPlugin extends JavaPlugin {

    private BookManager manager;
    private ChatPrompt chatPrompt;
    private BukkitTask checkTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new BookManager(this);
        manager.load();
        chatPrompt = new ChatPrompt(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new BookProtectionListener(this), this);
        pm.registerEvents(new EffectListener(this), this);
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(chatPrompt, this);

        EffectBooksCommand command = new EffectBooksCommand(this);
        PluginCommand cmd = getCommand("effectbooks");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        startTask();
        manager.refreshAll();
        getLogger().info("Loaded " + manager.books().size() + " effect book(s).");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.saveData();
    }

    /** Reloads config.yml + data.yml and resyncs everyone. */
    public void reload() {
        manager.load();
        startTask();
        manager.refreshAll();
    }

    private void startTask() {
        if (checkTask != null) checkTask.cancel();
        long interval = Math.max(5, getConfig().getLong("check-interval-ticks", 40));
        checkTask = getServer().getScheduler().runTaskTimer(this, () -> {
            manager.refreshAll();
            manager.pulseInstantEffects();
        }, interval, interval);
    }

    public BookManager manager() {
        return manager;
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    public String prefix() {
        return ChatColor.DARK_PURPLE + "[EffectBooks] " + ChatColor.RESET;
    }
}

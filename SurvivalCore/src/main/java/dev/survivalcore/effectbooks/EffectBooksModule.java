package dev.survivalcore.effectbooks;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.effectbooks.command.BookCommand;
import dev.survivalcore.effectbooks.command.EffectBooksCommand;
import dev.survivalcore.effectbooks.gui.ChatPrompt;
import dev.survivalcore.effectbooks.gui.MenuListener;
import dev.survivalcore.effectbooks.listener.BookProtectionListener;
import dev.survivalcore.effectbooks.listener.BookSlotListener;
import dev.survivalcore.effectbooks.listener.EffectListener;
import dev.survivalcore.effectbooks.listener.PlayerListener;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Effect books: holders write player names in a glowing book and everyone written in it keeps
 * that potion effect. Settings live in effectbooks.yml and the names in effectbooks-data.yml.
 */
public class EffectBooksModule extends Module {

    private final File configFile;
    private FileConfiguration config;
    private BookManager manager;
    private ChatPrompt chatPrompt;
    private BukkitTask checkTask;
    private final List<Listener> listeners = new ArrayList<>();

    public EffectBooksModule(SurvivalCore plugin) {
        super(plugin, "effect-books", "EffectBooks");
        this.configFile = new File(plugin.getDataFolder(), "effectbooks.yml");
        EffectBooksCommand admin = new EffectBooksCommand(this);
        plugin.bind("effectbooks", this, admin);
        plugin.bind("book", this, new BookCommand(this));
    }

    @Override
    public void enable() {
        migrateStandalonePlugin();
        if (!configFile.exists()) plugin.saveResource("effectbooks.yml", false);
        manager = new BookManager(this);
        manager.load();
        chatPrompt = new ChatPrompt(this);

        listeners.clear();
        listeners.add(new PlayerListener(this));
        listeners.add(new BookProtectionListener(this));
        listeners.add(new BookSlotListener(this));
        listeners.add(new EffectListener(this));
        listeners.add(new MenuListener(plugin));
        listeners.add(chatPrompt);
        for (Listener l : listeners) plugin.getServer().getPluginManager().registerEvents(l, plugin);

        startTask();
        manager.refreshAll();
        getLogger().info("Loaded " + manager.books().size() + " effect book(s).");
    }

    @Override
    public void disable() {
        if (checkTask != null) checkTask.cancel();
        checkTask = null;
        for (Listener l : listeners) HandlerList.unregisterAll(l);
        listeners.clear();
        if (manager != null) {
            manager.clearDisplays();
            manager.saveData();
        }
    }

    /**
     * The first time this runs on a server that used the standalone EffectBooks plugin, copy its
     * books and names over so nothing is lost.
     */
    private void migrateStandalonePlugin() {
        if (configFile.exists()) return;
        File oldFolder = new File(plugin.getDataFolder().getParentFile(), "EffectBooks");
        File oldConfig = new File(oldFolder, "config.yml");
        if (!oldConfig.isFile()) return;
        try {
            plugin.getDataFolder().mkdirs();
            Files.copy(oldConfig.toPath(), configFile.toPath());
            File oldData = new File(oldFolder, "data.yml");
            File newData = new File(plugin.getDataFolder(), "effectbooks-data.yml");
            if (oldData.isFile() && !newData.exists()) Files.copy(oldData.toPath(), newData.toPath());
            getLogger().info("Imported books from plugins/EffectBooks. You can delete the old EffectBooks jar.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not import plugins/EffectBooks", e);
        }
    }

    /** Reloads effectbooks.yml + data and resyncs everyone. */
    public void reload() {
        manager.load();
        startTask();
        manager.refreshAll();
    }

    private void startTask() {
        if (checkTask != null) checkTask.cancel();
        long interval = Math.max(5, getConfig().getLong("check-interval-ticks", 40));
        checkTask = getServer().getScheduler().runTaskTimer(plugin, () -> {
            manager.refreshAll();
            manager.pulseInstantEffects();
        }, interval, interval);
    }

    // ------------------------------------------------------------------ plugin-like accessors

    public JavaPlugin core() {
        return plugin;
    }

    public Server getServer() {
        return plugin.getServer();
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    public FileConfiguration getConfig() {
        if (config == null) reloadConfig();
        return config;
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaults = plugin.getResource("effectbooks.yml");
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
    }

    public void saveConfig() {
        try {
            getConfig().save(configFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save effectbooks.yml", e);
        }
    }

    public BookManager manager() {
        return manager;
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    @Override
    public String prefix() {
        return ChatColor.DARK_PURPLE + "[EffectBooks] " + ChatColor.RESET;
    }
}

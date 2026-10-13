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
        // Always registered: while books are switched off, effects they gave are taken off
        // players as they join (they'd otherwise keep them, with nothing protecting them).
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                if (plugin.isEnabled(EffectBooksModule.this)) return;
                new BookManager(EffectBooksModule.this).stripEffects(event.getPlayer());
            }
        }, plugin);
    }

    @Override
    public void enable() {
        migrateStandalonePlugin();
        if (!configFile.exists()) plugin.saveResource("effectbooks.yml", false);
        BookManager previous = manager;
        manager = new BookManager(this);
        manager.load();
        if (manager.isBroken() && previous != null && previous.hasLoaded()) {
            // effectbooks.yml is broken right now: keep running on the books we already had.
            manager = previous;
            manager.markBroken();
        }
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
     * Books were switched off in config.yml. Nothing protects their effects any more, so take them
     * off everyone online; switching books back on gives them back (who has what is remembered).
     */
    @Override
    public void turnedOff() {
        if (manager != null) manager.stripEffects();
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

    /** Whether effectbooks.yml can be read right now (without changing anything). */
    public boolean configFileParses() {
        if (!configFile.exists()) return true;
        try {
            new YamlConfiguration().load(configFile);
            return true;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            return false;
        }
    }

    /**
     * Reloads effectbooks.yml. Returns false (and keeps the previous settings) if the file has a
     * YAML error, so a typo can never be read as "no books".
     */
    public boolean reloadConfig() {
        YamlConfiguration loaded = new YamlConfiguration();
        boolean ok = true;
        try {
            if (configFile.exists()) loaded.load(configFile);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            getLogger().severe("effectbooks.yml can't be read, so nothing in it was changed: " + e.getMessage());
            ok = false;
        }
        if (!ok) {
            if (config == null) config = new YamlConfiguration();
            return false;
        }
        config = loaded;
        InputStream defaults = plugin.getResource("effectbooks.yml");
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        return true;
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

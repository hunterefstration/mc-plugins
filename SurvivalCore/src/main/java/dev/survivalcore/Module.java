package dev.survivalcore;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Listener;

/**
 * One feature of the plugin (vein miner, villager pickup...). Each module has its own section in
 * config.yml with an {@code enabled} flag, and is registered as an event listener while enabled.
 */
public abstract class Module implements Listener {

    protected final SurvivalCore plugin;
    private final String id;
    private final String displayName;

    protected Module(SurvivalCore plugin, String id, String displayName) {
        this.plugin = plugin;
        this.id = id;
        this.displayName = displayName;
    }

    public final String id() {
        return id;
    }

    public final String displayName() {
        return displayName;
    }

    /** This module's section of config.yml. Never null. */
    public ConfigurationSection config() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(id);
        return section != null ? section : plugin.getConfig().createSection(id);
    }

    /** Called after the config is loaded, before events are registered. */
    public void enable() {}

    /** Called on shutdown, reload, or when the module is turned off. */
    public void disable() {}

    public String prefix() {
        return ChatColor.DARK_AQUA + "[" + displayName + "] " + ChatColor.RESET;
    }
}

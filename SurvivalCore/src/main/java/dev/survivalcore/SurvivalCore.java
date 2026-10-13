package dev.survivalcore;

import dev.survivalcore.dupers.DupersModule;
import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.enderchest.EnderChestModule;
import dev.survivalcore.endportal.EndPortalModule;
import dev.survivalcore.pvp.AttributeSwapModule;
import dev.survivalcore.recipes.RecipesModule;
import dev.survivalcore.skins.SkinsModule;
import dev.survivalcore.stasis.RodStasisModule;
import dev.survivalcore.vaults.RevaultingModule;
import dev.survivalcore.veinminer.VeinMinerModule;
import dev.survivalcore.villagers.InstantRestockModule;
import dev.survivalcore.villagers.VillagerPickupModule;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class SurvivalCore extends JavaPlugin {

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Set<Module> enabled = new LinkedHashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        register(new EffectBooksModule(this));
        register(new VeinMinerModule(this));
        register(new RecipesModule(this));
        register(new VillagerPickupModule(this));
        register(new InstantRestockModule(this));
        register(new EndPortalModule(this));
        register(new DupersModule(this));
        register(new AttributeSwapModule(this));
        register(new RodStasisModule(this));
        register(new EnderChestModule(this));
        register(new RevaultingModule(this));
        register(new SkinsModule(this));

        bind("survivalcore", null, new AdminCommand());
        enableModules();
    }

    @Override
    public void onDisable() {
        disableModules();
    }

    private void register(Module module) {
        modules.put(module.id(), module);
    }

    public Module module(String id) {
        return modules.get(id);
    }

    public boolean isEnabled(Module module) {
        return enabled.contains(module);
    }

    private void enableModules() {
        for (Module module : modules.values()) {
            if (!getConfig().getBoolean(module.id() + ".enabled", true)) continue;
            try {
                module.enable();
                getServer().getPluginManager().registerEvents(module, this);
                enabled.add(module);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Could not enable " + module.displayName() + ", skipping it.", t);
                HandlerList.unregisterAll(module);
            }
        }
        List<String> names = new ArrayList<>();
        enabled.forEach(m -> names.add(m.displayName()));
        getLogger().info("Enabled: " + String.join(", ", names));
    }

    private void disableModules() {
        List<Module> list = new ArrayList<>(enabled);
        Collections.reverse(list);
        for (Module module : list) {
            HandlerList.unregisterAll(module);
            try {
                module.disable();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Error while disabling " + module.displayName(), t);
            }
        }
        enabled.clear();
        getServer().getScheduler().cancelTasks(this);
    }

    /** Reloads config.yml and restarts every module. */
    public void reloadAll() {
        disableModules();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        enableModules();
    }

    /**
     * Hooks a command from plugin.yml to a module. While the module is turned off the command just
     * says so instead of running.
     */
    public void bind(String name, Module owner, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            if (owner != null && !isEnabled(owner)) {
                sender.sendMessage(ChatColor.RED + owner.displayName() + " is turned off in config.yml.");
                return true;
            }
            return executor.onCommand(sender, cmd, label, args);
        });
        command.setTabCompleter((sender, cmd, label, args) ->
                owner != null && !isEnabled(owner) ? List.of() : executor.onTabComplete(sender, cmd, label, args));
    }

    private final class AdminCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload")) {
                reloadAll();
                sender.sendMessage(ChatColor.GREEN + "SurvivalCore reloaded. " + enabled.size() + " feature(s) on.");
                return true;
            }
            sender.sendMessage(ChatColor.GOLD + "SurvivalCore " + getPluginMeta().getVersion());
            for (Module module : modules.values()) {
                boolean on = enabled.contains(module);
                sender.sendMessage((on ? ChatColor.GREEN + "  ON  " : ChatColor.RED + "  OFF ")
                        + ChatColor.WHITE + module.displayName() + ChatColor.DARK_GRAY + " (" + module.id() + ")");
            }
            sender.sendMessage(ChatColor.GRAY + "Turn features on/off in config.yml, then /" + label + " reload");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("reload") : List.of();
        }
    }
}

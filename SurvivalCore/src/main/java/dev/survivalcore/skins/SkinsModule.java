package dev.survivalcore.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Skins without SkinsRestorer. /skin &lt;name&gt; wears any Minecraft account's skin, /skin clear goes
 * back to your own. On offline-mode servers everyone automatically gets the skin of the account
 * that matches their username. Chosen skins are saved in skins.yml.
 */
public final class SkinsModule extends Module {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final long REFRESH_MS = 1000L * 60 * 60 * 24 * 3; // re-download cached skins every 3 days

    private final File file;
    private YamlConfiguration data;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /** A downloaded skin: the signed "textures" property from Mojang. */
    record Skin(String source, String value, String signature, long fetched) {}

    public SkinsModule(SurvivalCore plugin) {
        super(plugin, "skins", "Skins");
        this.file = new File(plugin.getDataFolder(), "skins.yml");
        plugin.bind("skin", this, new SkinCommand());
    }

    @Override
    public void enable() {
        data = YamlConfiguration.loadConfiguration(file);
    }

    private synchronized void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save skins.yml", e);
        }
    }

    private synchronized Skin stored(UUID player) {
        ConfigurationSection s = data.getConfigurationSection("players." + player);
        if (s == null || s.getString("value") == null) return null;
        return new Skin(s.getString("source"), s.getString("value"), s.getString("signature"), s.getLong("fetched"));
    }

    private synchronized void store(UUID player, Skin skin) {
        if (skin == null) {
            data.set("players." + player, null);
        } else {
            String path = "players." + player;
            data.set(path + ".source", skin.source());
            data.set(path + ".value", skin.value());
            data.set(path + ".signature", skin.signature());
            data.set(path + ".fetched", skin.fetched());
        }
        save();
    }

    /** Looks up a Minecraft account's current skin. Blocking: call off the main thread. */
    private static Skin download(String name) {
        PlayerProfile profile = Bukkit.createProfile(name);
        if (!profile.complete(true, true)) return null; // always ask Mojang, even on offline-mode servers
        for (ProfileProperty p : profile.getProperties()) {
            if (p.getName().equals("textures")) {
                return new Skin(profile.getName() != null ? profile.getName() : name, p.getValue(), p.getSignature(),
                        System.currentTimeMillis());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ joining

    /** Runs before the player is in the game, so their skin is right from the start (no flicker). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = event.getUniqueId();
        Skin skin = stored(id);
        try {
            if (skin != null && System.currentTimeMillis() - skin.fetched() > REFRESH_MS) {
                Skin fresh = download(skin.source());
                if (fresh != null) {
                    skin = fresh;
                    store(id, fresh);
                }
            } else if (skin == null && !Bukkit.getOnlineMode() && config().getBoolean("offline-mode-auto-skin", true)) {
                skin = download(event.getName());
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Skin lookup failed for " + event.getName(), t);
        }
        if (skin == null) return;
        PlayerProfile profile = event.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        event.setPlayerProfile(profile);
    }

    private void apply(Player player, Skin skin) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        if (skin != null) profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        player.setPlayerProfile(profile); // Paper re-sends the player to everyone, including themselves
    }

    /** Changes a player's skin to the named account's (or back to their own if name is null). */
    private void change(CommandSender sender, Player target, String name) {
        boolean reset = name == null;
        String lookup = reset ? target.getName() : name;
        sender.sendMessage(prefix() + ChatColor.GRAY + "Fetching skin...");
        UUID targetId = target.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Skin skin;
            try {
                skin = download(lookup);
            } catch (Throwable t) {
                skin = null;
            }
            Skin result = skin;
            if (!reset && result == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(prefix() + ChatColor.RED + "No Minecraft account called '" + lookup + "'."));
                return;
            }
            // Resetting on an online-mode server: the account's own skin. Offline: their name's skin (or none).
            store(targetId, reset ? null : result);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(targetId);
                if (online == null) return;
                apply(online, result);
                if (reset) sender.sendMessage(prefix() + ChatColor.GREEN + "Skin reset.");
                else sender.sendMessage(prefix() + ChatColor.GREEN + "Now wearing " + result.source() + "'s skin.");
            });
        });
    }

    private final class SkinCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(prefix() + ChatColor.GRAY + "/" + label + " <username>" + ChatColor.DARK_GRAY
                        + " - wear that player's skin");
                sender.sendMessage(prefix() + ChatColor.GRAY + "/" + label + " clear" + ChatColor.DARK_GRAY
                        + " - go back to your own skin");
                if (sender.hasPermission("survivalcore.skin.others")) {
                    sender.sendMessage(prefix() + ChatColor.GRAY + "/" + label + " <username|clear> <player>"
                            + ChatColor.DARK_GRAY + " - change someone else's skin");
                }
                return true;
            }
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("survivalcore.skin.others")) {
                    sender.sendMessage(prefix() + ChatColor.RED + "You can only change your own skin.");
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix() + ChatColor.RED + args[1] + " isn't online.");
                    return true;
                }
            } else if (sender instanceof Player p) {
                target = p;
            } else {
                sender.sendMessage("Usage: /" + label + " <username|clear> <player>");
                return true;
            }

            String arg = args[0];
            boolean reset = arg.equalsIgnoreCase("clear") || arg.equalsIgnoreCase("reset");
            if (!reset && !NAME.matcher(arg).matches()) {
                sender.sendMessage(prefix() + ChatColor.RED + "'" + arg + "' isn't a valid username.");
                return true;
            }
            if (sender instanceof Player p && !p.hasPermission("survivalcore.skin.others")) {
                long cooldown = Math.max(0, config().getLong("cooldown-seconds", 30)) * 1000L;
                long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
                long wait = last + cooldown - System.currentTimeMillis();
                if (wait > 0) {
                    sender.sendMessage(prefix() + ChatColor.RED + "Wait " + (wait / 1000 + 1) + "s before changing again.");
                    return true;
                }
                cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
            }
            change(sender, target, reset ? null : arg);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> options = new ArrayList<>();
            if (args.length == 1) {
                options.add("clear");
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            } else if (args.length == 2 && sender.hasPermission("survivalcore.skin.others")) {
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            }
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
            return options;
        }
    }
}

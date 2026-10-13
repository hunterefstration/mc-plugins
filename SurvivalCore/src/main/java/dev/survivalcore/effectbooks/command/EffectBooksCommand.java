package dev.survivalcore.effectbooks.command;

import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.gui.MainMenu;
import dev.survivalcore.effectbooks.model.Book;
import dev.survivalcore.effectbooks.util.Effects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EffectBooksCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "list", "add", "delete", "help");

    private final EffectBooksModule plugin;

    public EffectBooksCommand(EffectBooksModule plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String p = plugin.prefix();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new MainMenu(plugin, 0).open(player);
            } else {
                help(sender, label);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(p + ChatColor.GREEN + "Reloaded. " + plugin.manager().books().size() + " book(s) active.");
            }
            case "list" -> {
                if (plugin.manager().books().isEmpty()) {
                    sender.sendMessage(p + ChatColor.GRAY + "No effect books yet.");
                }
                for (Book b : plugin.manager().sortedBooks()) {
                    sender.sendMessage(p + ChatColor.LIGHT_PURPLE + b.displayName() + ChatColor.GRAY + " held by "
                            + ChatColor.WHITE + b.holder() + ChatColor.GRAY + " -> "
                            + (b.targets().isEmpty() ? "(nobody)" : String.join(", ", b.targets())));
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(p + ChatColor.RED + "Usage: /" + label + " add <holder> <effect> [level]");
                    return true;
                }
                PotionEffectType effect = Effects.parse(args[2]);
                if (effect == null) {
                    sender.sendMessage(p + ChatColor.RED + "Unknown effect '" + args[2] + "'.");
                    return true;
                }
                int level = 1;
                if (args.length >= 4) {
                    try {
                        level = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(p + ChatColor.RED + "Level must be a number.");
                        return true;
                    }
                }
                try {
                    Book book = plugin.manager().create(args[1], effect, level);
                    sender.sendMessage(p + ChatColor.GREEN + "Created " + book.displayName() + " for " + book.holder() + ".");
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(p + ChatColor.RED + e.getMessage());
                }
            }
            case "delete", "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(p + ChatColor.RED + "Usage: /" + label + " delete <holder> <effect>");
                    return true;
                }
                PotionEffectType effect = Effects.parse(args[2]);
                Book book = effect == null ? null : plugin.manager().find(args[1], effect);
                if (book == null) {
                    sender.sendMessage(p + ChatColor.RED + args[1] + " has no book of that effect.");
                    return true;
                }
                plugin.manager().delete(book);
                sender.sendMessage(p + ChatColor.GREEN + "Deleted " + book.displayName() + " (" + book.holder()
                        + "). Its effect was cleared from everyone in it.");
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        String p = plugin.prefix();
        sender.sendMessage(p + ChatColor.GOLD + "Effect Books");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + ChatColor.GRAY + " - open the admin menu");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " list" + ChatColor.GRAY + " - list all books");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " add <holder> <effect> [level]" + ChatColor.GRAY + " - create a book");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " delete <holder> <effect>" + ChatColor.GRAY + " - delete a book");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload" + ChatColor.GRAY + " - reload config.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            Bukkit.getOnlinePlayers().forEach(pl -> options.add(pl.getName()));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove"))) {
            plugin.manager().books().forEach(b -> { if (!options.contains(b.holder())) options.add(b.holder()); });
        } else if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            Effects.selectable().forEach(t -> options.add(Effects.id(t)));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove"))) {
            plugin.manager().books().forEach(b -> { if (b.isHolder(args[1])) options.add(Effects.id(b.effect())); });
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}

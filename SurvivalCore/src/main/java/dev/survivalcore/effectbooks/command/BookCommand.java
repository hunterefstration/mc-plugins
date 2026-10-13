package dev.survivalcore.effectbooks.command;

import dev.survivalcore.effectbooks.EffectBooksModule;
import dev.survivalcore.effectbooks.listener.BookSlotListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /book - takes your effect book out to write in it (same as clicking it in the crafting slot). */
public final class BookCommand implements TabExecutor {

    private final EffectBooksModule plugin;

    public BookCommand(EffectBooksModule plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            BookSlotListener.open(plugin, player);
        } else {
            sender.sendMessage("Only players can use this.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}

package dev.survivalcore.effectbooks.gui;

import dev.survivalcore.effectbooks.EffectBooksModule;
import org.bukkit.ChatColor;
import org.bukkit.Material;

public final class ConfirmMenu extends Menu {

    private final String question;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(EffectBooksModule plugin, String question, Runnable onConfirm, Runnable onCancel) {
        super(plugin, 3, "Are you sure?");
        this.question = question;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected void draw() {
        for (int slot = 0; slot < 27; slot++) set(slot, Icons.filler(), null);
        set(11, Icons.icon(Material.LIME_WOOL, ChatColor.GREEN + "Yes", question), e -> onConfirm.run());
        set(13, Icons.icon(Material.PAPER, ChatColor.WHITE + question, "This can't be undone."), null);
        set(15, Icons.icon(Material.RED_WOOL, ChatColor.RED + "No, go back"), e -> onCancel.run());
    }
}

package dev.effectbooks.gui;

import dev.effectbooks.EffectBooksPlugin;
import dev.effectbooks.util.Effects;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.function.Consumer;

/** Grid of every non-instant effect. */
public final class EffectPickerMenu extends Menu {

    private static final int PER_PAGE = 45;
    private final Consumer<PotionEffectType> onPick;
    private final Runnable onBack;
    private final String title;
    private final int page;

    public EffectPickerMenu(EffectBooksPlugin plugin, String title, Consumer<PotionEffectType> onPick,
                            Runnable onBack, int page) {
        super(plugin, 6, title.length() > 32 ? title.substring(0, 32) : title);
        this.title = title;
        this.onPick = onPick;
        this.onBack = onBack;
        this.page = page;
    }

    @Override
    protected void draw() {
        List<PotionEffectType> effects = Effects.selectable();
        int pages = Math.max(1, (effects.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.min(this.page, pages - 1);

        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= effects.size()) break;
            PotionEffectType type = effects.get(index);
            String description = Effects.description(type);
            set(i, Icons.effect(type, ChatColor.AQUA + Effects.prettyName(type),
                            description == null ? "" : description,
                            type.isInstant() ? "Instant: given again every few seconds" : "Lasts forever",
                            "",
                            ChatColor.YELLOW + "Click to choose"),
                    e -> onPick.accept(type));
        }

        for (int slot = 45; slot < 54; slot++) set(slot, Icons.filler(), null);
        if (page > 0) {
            set(45, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Previous page"), e ->
                    new EffectPickerMenu(plugin, title, onPick, onBack, page - 1).open((Player) e.getWhoClicked()));
        }
        if (page < pages - 1) {
            set(53, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Next page"), e ->
                    new EffectPickerMenu(plugin, title, onPick, onBack, page + 1).open((Player) e.getWhoClicked()));
        }
        set(49, Icons.icon(Material.ARROW, ChatColor.YELLOW + "Back"), e -> onBack.run());
    }
}

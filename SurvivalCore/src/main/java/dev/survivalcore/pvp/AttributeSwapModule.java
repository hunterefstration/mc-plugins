package dev.survivalcore.pvp;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.PaperSettings;

/**
 * Attribute swapping: hit with one item and switch to another in the same tick, so the hit uses
 * the first item's attributes and the second item's enchantments (e.g. sword damage with a mace or
 * axe effect). Paper stops it by refreshing equipment on every player action; this turns Paper's
 * {@code update-equipment-on-player-actions} switch off, which restores vanilla timing exactly
 * (attributes and the attack cooldown update on the player's tick, like vanilla).
 */
public final class AttributeSwapModule extends Module {

    private PaperSettings paper;

    public AttributeSwapModule(SurvivalCore plugin) {
        super(plugin, "attribute-swap", "AttributeSwap");
    }

    @Override
    public void enable() {
        paper = new PaperSettings(plugin, displayName());
        paper.want("unsupportedSettings.updateEquipmentOnPlayerActions", false);
        paper.apply();
    }

    @Override
    public void disable() {
        if (paper != null) paper.restore();
    }
}

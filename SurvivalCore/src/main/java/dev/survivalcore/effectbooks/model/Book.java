package dev.survivalcore.effectbooks.model;

import dev.survivalcore.effectbooks.util.Effects;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One effect book: who holds it, what it grants, and who has been written in it. */
public final class Book {

    private final UUID id;
    private String holder;
    private PotionEffectType effect;
    private int level;
    private final List<String> targets = new ArrayList<>();

    public Book(UUID id, String holder, PotionEffectType effect, int level) {
        this.id = id;
        this.holder = holder;
        this.effect = effect;
        this.level = level;
    }

    public UUID id() { return id; }
    public String holder() { return holder; }
    public PotionEffectType effect() { return effect; }
    public int level() { return level; }
    public int amplifier() { return level - 1; }
    public List<String> targets() { return targets; }

    public void setHolder(String holder) { this.holder = holder; }
    public void setEffect(PotionEffectType effect) { this.effect = effect; }
    public void setLevel(int level) { this.level = level; }

    public boolean isHolder(String name) {
        return holder.equalsIgnoreCase(name);
    }

    public boolean hasTarget(String name) {
        for (String t : targets) {
            if (t.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** e.g. "Book of Speed" or "Book of Strength II". */
    public String displayName() {
        return "Book of " + Effects.prettyName(effect) + (level > 1 ? " " + Effects.roman(level) : "");
    }

    /** Config form, e.g. "SPEED" or "STRENGTH:2". */
    public String spec() {
        return Effects.id(effect).toUpperCase(java.util.Locale.ROOT) + (level > 1 ? ":" + level : "");
    }
}

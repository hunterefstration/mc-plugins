package dev.survivalcore.effectbooks.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class Effects {

    public static final int MAX_LEVEL = 255;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** Old Bukkit names people still type. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("slow", "slowness"),
            Map.entry("fast_digging", "haste"),
            Map.entry("slow_digging", "mining_fatigue"),
            Map.entry("increase_damage", "strength"),
            Map.entry("jump", "jump_boost"),
            Map.entry("confusion", "nausea"),
            Map.entry("damage_resistance", "resistance"),
            Map.entry("heal", "instant_health"),
            Map.entry("harm", "instant_damage"),
            Map.entry("bad_luck", "unluck"));

    private static final Map<String, String> NAME_OVERRIDES = Map.of(
            "unluck", "Bad Luck",
            "dolphins_grace", "Dolphin's Grace");

    /** effect id -> item(s) to show for it; alternatives separated by '|'. */
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("speed", "SUGAR"),
            Map.entry("slowness", "SOUL_SAND"),
            Map.entry("haste", "REDSTONE"),
            Map.entry("mining_fatigue", "PRISMARINE_SHARD"),
            Map.entry("strength", "BLAZE_POWDER"),
            Map.entry("instant_health", "GLISTERING_MELON_SLICE"),
            Map.entry("instant_damage", "FERMENTED_SPIDER_EYE"),
            Map.entry("jump_boost", "RABBIT_FOOT"),
            Map.entry("nausea", "PUFFERFISH"),
            Map.entry("regeneration", "GHAST_TEAR"),
            Map.entry("resistance", "SHIELD"),
            Map.entry("fire_resistance", "MAGMA_CREAM"),
            Map.entry("water_breathing", "TURTLE_SCUTE|SCUTE"),
            Map.entry("invisibility", "GLASS"),
            Map.entry("blindness", "INK_SAC"),
            Map.entry("night_vision", "GOLDEN_CARROT"),
            Map.entry("hunger", "ROTTEN_FLESH"),
            Map.entry("weakness", "GRAY_DYE"),
            Map.entry("poison", "SPIDER_EYE"),
            Map.entry("wither", "WITHER_ROSE"),
            Map.entry("health_boost", "GOLDEN_APPLE"),
            Map.entry("absorption", "ENCHANTED_GOLDEN_APPLE"),
            Map.entry("saturation", "COOKED_BEEF"),
            Map.entry("glowing", "GLOWSTONE_DUST"),
            Map.entry("levitation", "SHULKER_SHELL"),
            Map.entry("luck", "EMERALD"),
            Map.entry("unluck", "DEAD_BUSH"),
            Map.entry("slow_falling", "PHANTOM_MEMBRANE"),
            Map.entry("conduit_power", "CONDUIT"),
            Map.entry("dolphins_grace", "TROPICAL_FISH"),
            Map.entry("bad_omen", "OMINOUS_BOTTLE"),
            Map.entry("hero_of_the_village", "BELL"),
            Map.entry("darkness", "SCULK_SHRIEKER"),
            Map.entry("trial_omen", "OMINOUS_TRIAL_KEY"),
            Map.entry("raid_omen", "CROSSBOW"),
            Map.entry("wind_charged", "WIND_CHARGE"),
            Map.entry("weaving", "COBWEB"),
            Map.entry("oozing", "SLIME_BALL"),
            Map.entry("infested", "INFESTED_STONE"),
            Map.entry("breath_of_the_nautilus", "NAUTILUS_SHELL"));

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("speed", "Move faster"),
            Map.entry("slowness", "Move slower"),
            Map.entry("haste", "Mine and attack faster"),
            Map.entry("mining_fatigue", "Mine and attack much slower"),
            Map.entry("strength", "Deal more melee damage"),
            Map.entry("instant_health", "Heals a burst of health every few seconds"),
            Map.entry("instant_damage", "Deals a burst of damage every few seconds - can kill!"),
            Map.entry("jump_boost", "Jump higher, take less fall damage"),
            Map.entry("nausea", "Wobbly, warping screen"),
            Map.entry("regeneration", "Slowly regain health"),
            Map.entry("resistance", "Take less damage"),
            Map.entry("fire_resistance", "Immune to fire and lava"),
            Map.entry("water_breathing", "Breathe underwater"),
            Map.entry("invisibility", "Invisible (armor still shows)"),
            Map.entry("blindness", "Can barely see"),
            Map.entry("night_vision", "See clearly in the dark"),
            Map.entry("hunger", "Food drains faster"),
            Map.entry("weakness", "Deal less melee damage"),
            Map.entry("poison", "Lose health (stops at half a heart)"),
            Map.entry("wither", "Lose health - can kill!"),
            Map.entry("health_boost", "Extra max hearts"),
            Map.entry("absorption", "Extra golden hearts"),
            Map.entry("saturation", "Hunger always refills"),
            Map.entry("glowing", "Outlined and visible through walls"),
            Map.entry("levitation", "Float upward nonstop"),
            Map.entry("luck", "Better fishing and chest loot"),
            Map.entry("unluck", "Worse fishing and chest loot"),
            Map.entry("slow_falling", "Fall slowly, no fall damage"),
            Map.entry("conduit_power", "Breathe, see and mine fast underwater"),
            Map.entry("dolphins_grace", "Swim much faster"),
            Map.entry("bad_omen", "Turns into a raid/trial omen"),
            Map.entry("hero_of_the_village", "Villager discounts and gifts"),
            Map.entry("darkness", "Screen keeps pulsing dark"),
            Map.entry("trial_omen", "Trial spawners turn ominous"),
            Map.entry("raid_omen", "Raid omen (never runs out, so no raid)"),
            Map.entry("wind_charged", "Bursts of wind when they die"),
            Map.entry("weaving", "Spawns cobwebs when they die"),
            Map.entry("oozing", "Spawns slimes when they die"),
            Map.entry("infested", "Silverfish may spawn when they're hit"),
            Map.entry("breath_of_the_nautilus", "Air lasts longer underwater"));

    private Effects() {}

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Parses "speed", "SPEED", "minecraft:speed", "jump" ... Returns null if unknown. */
    public static PotionEffectType parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        s = ALIASES.getOrDefault(s, s);
        if (s.isEmpty() || !s.matches("[a-z0-9_./]+")) return null;
        return Registry.EFFECT.get(NamespacedKey.minecraft(s));
    }

    /** Every effect in the game, sorted by name. */
    public static List<PotionEffectType> selectable() {
        List<PotionEffectType> list = new ArrayList<>();
        for (PotionEffectType type : Registry.EFFECT) list.add(type);
        list.sort(Comparator.comparing(Effects::prettyName));
        return list;
    }

    public static String id(PotionEffectType type) {
        return type.getKey().getKey();
    }

    /** The in-game name: "jump_boost" -> "Jump Boost", "hero_of_the_village" -> "Hero of the Village". */
    public static String prettyName(PotionEffectType type) {
        String id = id(type);
        String special = NAME_OVERRIDES.get(id);
        if (special != null) return special;
        String[] words = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (sb.length() > 0 && (w.equals("of") || w.equals("the"))) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /** One-line explanation for the effect picker, or null if unknown. */
    public static String description(PotionEffectType type) {
        return DESCRIPTIONS.get(id(type));
    }

    /** An item that represents the effect in menus, or null to fall back to a coloured potion. */
    public static Material iconMaterial(PotionEffectType type) {
        String names = ICONS.get(id(type));
        if (names == null) return null;
        for (String name : names.split("\\|")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isItem()) return m;
        }
        return null;
    }

    public static String roman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                n -= values[i];
                sb.append(numerals[i]);
            }
        }
        return sb.toString();
    }
}

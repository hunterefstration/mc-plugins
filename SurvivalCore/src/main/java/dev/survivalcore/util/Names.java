package dev.survivalcore.util;

import java.util.Locale;

public final class Names {

    private Names() {}

    /** "DEEPSLATE_IRON_ORE" / "minecraft:iron_ore" -> "Deepslate Iron Ore". */
    public static String pretty(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : s.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}

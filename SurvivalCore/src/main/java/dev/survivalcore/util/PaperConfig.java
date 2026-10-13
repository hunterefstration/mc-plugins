package dev.survivalcore.util;

import java.lang.reflect.Field;

/**
 * Reads and changes Paper's own settings (config/paper-global.yml) while the server runs. The
 * change is in memory only: paper-global.yml itself is never edited, and the original value comes
 * back when the plugin is disabled.
 */
public final class PaperConfig {

    private PaperConfig() {}

    private static Object global() throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
        return type.getMethod("get").invoke(null);
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field f = owner.getClass().getField(name);
        f.setAccessible(true);
        return f;
    }

    /** Gets e.g. ("unsupportedSettings", "allowPistonDuplication"). */
    public static boolean get(String section, String name) throws ReflectiveOperationException {
        Object part = field(global(), section).get(global());
        return field(part, name).getBoolean(part);
    }

    /** Sets a boolean setting and returns what it was before. */
    public static boolean set(String section, String name, boolean value) throws ReflectiveOperationException {
        Object part = field(global(), section).get(global());
        Field f = field(part, name);
        boolean old = f.getBoolean(part);
        f.setBoolean(part, value);
        return old;
    }

    /** "unsupportedSettings" -> "unsupported-settings" (how Paper names them in the YAML file). */
    static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('-').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** The value currently written in config/paper-global.yml, or null if it can't be read. */
    public static Boolean fileValue(String section, String name) {
        java.io.File file = new java.io.File("config", "paper-global.yml");
        if (!file.isFile()) return null;
        try {
            Object root = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(file.toPath()));
            if (root instanceof java.util.Map<?, ?> top && top.get(kebab(section)) instanceof java.util.Map<?, ?> part
                    && part.get(kebab(name)) instanceof Boolean b) {
                return b;
            }
        } catch (java.io.IOException | RuntimeException ignored) {
        }
        return null;
    }
}

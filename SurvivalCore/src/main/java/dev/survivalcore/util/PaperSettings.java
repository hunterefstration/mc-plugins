package dev.survivalcore.util;

import dev.survivalcore.SurvivalCore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * A set of Paper settings a module wants changed. They are applied on enable, put back on
 * disable, and re-applied every 30 seconds in case /paper reload reset them.
 */
public final class PaperSettings {

    private final SurvivalCore plugin;
    private final String owner;
    private final Map<String, Boolean> wanted = new LinkedHashMap<>();
    private final Map<String, Boolean> originals = new LinkedHashMap<>();
    private org.bukkit.scheduler.BukkitTask task;

    public PaperSettings(SurvivalCore plugin, String owner) {
        this.plugin = plugin;
        this.owner = owner;
    }

    /** {@code key} is "section.field", e.g. "unsupportedSettings.allowPistonDuplication". */
    public void want(String key, boolean value) {
        wanted.put(key, value);
    }

    public void apply() {
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            try {
                boolean old = PaperConfig.set(parts[0], parts[1], e.getValue());
                originals.putIfAbsent(e.getKey(), old);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, owner + ": couldn't change Paper setting " + e.getKey()
                        + " (is this a Paper server?)", ex);
            }
        }
        if (task == null && !wanted.isEmpty()) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reapply, 600L, 600L);
        }
    }

    private void reapply() {
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            try {
                if (PaperConfig.get(parts[0], parts[1]) != e.getValue()) PaperConfig.set(parts[0], parts[1], e.getValue());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
    }

    public void restore() {
        if (task != null) task.cancel();
        task = null;
        for (Map.Entry<String, Boolean> e : originals.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            try {
                PaperConfig.set(parts[0], parts[1], e.getValue());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        originals.clear();
        wanted.clear();
    }
}

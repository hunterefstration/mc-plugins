package dev.survivalcore.util;

import dev.survivalcore.SurvivalCore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * A set of Paper settings a module wants changed. They are applied on enable, put back on
 * disable, and re-applied within a second if /paper reload resets them.
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
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reapply, 20L, 20L);
        }
    }

    private void reapply() {
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            String[] parts = e.getKey().split("\\.");
            try {
                boolean now = PaperConfig.get(parts[0], parts[1]);
                if (now != e.getValue()) {
                    // /paper reload read paper-global.yml again: that's the admin's value now.
                    originals.put(e.getKey(), now);
                    PaperConfig.set(parts[0], parts[1], e.getValue());
                }
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
                // Only undo our own change (if something else changed it since, leave that alone).
                Boolean ours = wanted.get(e.getKey());
                if (ours != null && PaperConfig.get(parts[0], parts[1]) != ours) continue;
                // Put back what paper-global.yml says now (it may have been edited since), or the
                // value from before we changed it if the file can't be read.
                Boolean inFile = PaperConfig.fileValue(parts[0], parts[1]);
                PaperConfig.set(parts[0], parts[1], inFile != null ? inFile : e.getValue());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        originals.clear();
        wanted.clear();
    }
}

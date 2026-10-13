package dev.survivalcore.veinminer;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Breaking one ore or log breaks the whole connected vein / tree, sneaking or not. Each extra block
 * is broken as if the player broke it themselves, so Fortune, Silk Touch, Unbreaking, durability,
 * XP and block-logging plugins (CoreProtect) all work normally.
 */
public final class VeinMinerModule extends Module {

    private final NamespacedKey disabledKey;
    /** Players whose vein is being broken right now (so our own block breaks don't chain). */
    private final Map<UUID, Location> active = new HashMap<>();

    private final Map<Material, String> oreGroups = new HashMap<>();
    private final Set<Material> extraBlocks = new HashSet<>();
    private int maxOres;
    private int maxLogs;
    private boolean requireTool;
    private boolean logsNeedAxe;
    private boolean protectTool;
    private boolean dropsAtFirstBlock;
    private boolean creative;

    public VeinMinerModule(SurvivalCore plugin) {
        super(plugin, "vein-miner", "VeinMiner");
        this.disabledKey = new NamespacedKey(plugin, "veinminer_off");
        plugin.bind("veinminer", this, new ToggleCommand());
    }

    @Override
    public void enable() {
        maxOres = Math.max(1, config().getInt("max-ores", 64));
        maxLogs = Math.max(1, config().getInt("max-logs", 256));
        requireTool = config().getBoolean("require-correct-tool", true);
        logsNeedAxe = config().getBoolean("logs-need-axe", true);
        protectTool = config().getBoolean("protect-tool", true);
        dropsAtFirstBlock = config().getBoolean("drops-at-first-block", true);
        creative = config().getBoolean("work-in-creative", false);

        oreGroups.clear();
        group("coal", Tag.COAL_ORES);
        group("iron", Tag.IRON_ORES);
        group("copper", Tag.COPPER_ORES);
        group("gold", Tag.GOLD_ORES);
        group("redstone", Tag.REDSTONE_ORES);
        group("lapis", Tag.LAPIS_ORES);
        group("diamond", Tag.DIAMOND_ORES);
        group("emerald", Tag.EMERALD_ORES);
        oreGroups.put(Material.NETHER_QUARTZ_ORE, "quartz");
        if (config().getBoolean("ancient-debris", true)) oreGroups.put(Material.ANCIENT_DEBRIS, "debris");

        extraBlocks.clear();
        for (String name : config().getStringList("extra-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) extraBlocks.add(m);
            else plugin.getLogger().warning("vein-miner.extra-blocks: unknown block '" + name + "'");
        }
    }

    @Override
    public void disable() {
        active.clear();
    }

    private void group(String name, Tag<Material> tag) {
        for (Material m : tag.getValues()) oreGroups.put(m, name);
    }

    /** Which blocks count as "the same" as this one, or null if it isn't vein-mineable. */
    String groupOf(Material type) {
        String ore = oreGroups.get(type);
        if (ore != null) return "ore:" + ore;
        if (Tag.LOGS.isTagged(type)) return "log:" + type.name();
        if (extraBlocks.contains(type)) return "extra:" + type.name();
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (active.containsKey(player.getUniqueId())) return;
        if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) return;
        if (player.getGameMode() == GameMode.CREATIVE && !creative) return;
        if (player.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE)) return;
        if (!player.hasPermission("survivalcore.veinminer")) return;

        Block origin = event.getBlock();
        String group = groupOf(origin.getType());
        if (group == null) return;
        boolean isLog = group.startsWith("log:");

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (isLog) {
            if (logsNeedAxe && !Tag.ITEMS_AXES.isTagged(tool.getType())) return;
        } else if (requireTool && (!origin.isPreferredTool(tool) || origin.getDrops(tool, player).isEmpty())) {
            return;
        }

        List<Block> vein = findVein(origin, group, isLog ? maxLogs : maxOres);
        if (vein.isEmpty()) return;
        // Only real trees: a log wall or cabin has no natural leaves touching it.
        if (isLog && !isNaturalTree(origin, vein)) return;

        // Break it next tick, once the first block is gone.
        Location dropAt = origin.getLocation().add(0.5, 0.5, 0.5);
        plugin.getServer().getScheduler().runTask(plugin, () -> breakVein(player, vein, group, dropAt));
    }

    /** True if any of these logs touches natural (not player-placed) leaves or a nether "wart" canopy. */
    static boolean isNaturalTree(Block origin, List<Block> logs) {
        List<Block> all = new ArrayList<>(logs);
        all.add(origin);
        for (Block log : all) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block n = log.getRelative(dx, dy, dz);
                        Material t = n.getType();
                        if (Tag.LEAVES.isTagged(t)) {
                            if (n.getBlockData() instanceof org.bukkit.block.data.type.Leaves leaves && !leaves.isPersistent()) {
                                return true;
                            }
                        } else if (t == Material.NETHER_WART_BLOCK || t == Material.WARPED_WART_BLOCK) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Connected blocks of the same group (diagonals count), nearest first, not including the origin. */
    List<Block> findVein(Block origin, String group, int max) {
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> found = new ArrayList<>();
        seen.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < max) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (!seen.add(next)) continue;
                        if (!group.equals(groupOf(next.getType()))) continue;
                        found.add(next);
                        queue.add(next);
                        if (found.size() >= max) break;
                    }
                }
            }
        }
        Location o = origin.getLocation();
        found.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(o)));
        return found;
    }

    private void breakVein(Player player, List<Block> vein, String group, Location dropAt) {
        if (!player.isOnline() || player.isDead()) return;
        active.put(player.getUniqueId(), dropAt);
        try {
            for (Block block : vein) {
                if (!group.equals(groupOf(block.getType()))) continue; // changed meanwhile
                if (protectTool && aboutToBreak(player.getInventory().getItemInMainHand())) {
                    player.sendActionBar(ChatColor.RED + "VeinMiner stopped so your tool doesn't break.");
                    break;
                }
                if (!player.breakBlock(block)) continue; // another plugin (claims, CoreProtect...) said no
            }
        } finally {
            active.remove(player.getUniqueId());
        }
    }

    /** Gather the extra drops at the block the player actually mined. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        if (!dropsAtFirstBlock) return;
        Location target = active.get(event.getPlayer().getUniqueId());
        if (target == null || target.getWorld() != event.getBlock().getWorld()) return;
        // These items aren't in the world yet (so they can't be moved); drop copies at the target instead.
        for (Item item : event.getItems()) {
            target.getWorld().dropItem(target, item.getItemStack()).setVelocity(new org.bukkit.util.Vector());
        }
        event.getItems().clear();
    }

    private static boolean aboutToBreak(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) return false;
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) return false;
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : tool.getType().getMaxDurability();
        if (max <= 0) return false;
        return max - damageable.getDamage() <= 1;
    }

    private final class ToggleCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            boolean off = player.getPersistentDataContainer().has(disabledKey, PersistentDataType.BYTE);
            String arg = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
            boolean turnOn = switch (arg) {
                case "on" -> true;
                case "off" -> false;
                default -> off;
            };
            if (turnOn) player.getPersistentDataContainer().remove(disabledKey);
            else player.getPersistentDataContainer().set(disabledKey, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(prefix() + (turnOn ? ChatColor.GREEN + "Vein mining is on." : ChatColor.GRAY + "Vein mining is off."));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length != 1) return List.of();
            List<String> options = new ArrayList<>(List.of("on", "off", "toggle"));
            options.removeIf(o -> !o.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return options;
        }
    }
}

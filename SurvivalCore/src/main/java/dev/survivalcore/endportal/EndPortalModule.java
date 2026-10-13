package dev.survivalcore.endportal;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the End closed until an admin opens it. While closed, the eye that would complete a
 * portal (the 12th) can't be placed, and nobody can travel through an end portal that already
 * exists. Open it right away with /endportal open, or on a timer with /endportal open in 2h.
 */
public final class EndPortalModule extends Module {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhms])");
    private BukkitTask timer;

    public EndPortalModule(SurvivalCore plugin) {
        super(plugin, "end-portal", "EndPortal");
        plugin.bind("endportal", this, new EndPortalCommand());
    }

    @Override
    public void enable() {
        scheduleTimer();
    }

    @Override
    public void disable() {
        if (timer != null) timer.cancel();
        timer = null;
    }

    public boolean isOpen() {
        if (config().getBoolean("open", false)) return true;
        long opensAt = config().getLong("opens-at", 0);
        if (opensAt > 0 && System.currentTimeMillis() >= opensAt) {
            setOpen(true);
            return true;
        }
        return false;
    }

    private void setOpen(boolean open) {
        config().set("open", open);
        config().set("opens-at", 0);
        plugin.saveConfig();
        if (timer != null) timer.cancel();
        timer = null;
    }

    private void scheduleTimer() {
        if (timer != null) timer.cancel();
        timer = null;
        long opensAt = config().getLong("opens-at", 0);
        if (opensAt <= 0 || config().getBoolean("open", false)) return;
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() >= config().getLong("opens-at", Long.MAX_VALUE)) {
                setOpen(true);
                plugin.getServer().broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD
                        + "The End is now open!");
            }
        }, 20L, 20L);
    }

    // ------------------------------------------------------------------ blocking

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceEye(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) return;
        Block frame = event.getClickedBlock();
        if (frame.getType() != Material.END_PORTAL_FRAME) return;
        if (!(frame.getBlockData() instanceof EndPortalFrame data) || data.hasEye()) return;
        if (isOpen() || event.getPlayer().hasPermission("survivalcore.endportal.bypass")) return;

        if (wouldComplete(frame)) {
            event.setCancelled(true);
            tell(event.getPlayer());
        }
    }

    /** True if filling this frame would light a portal (every other frame around it has an eye). */
    static boolean wouldComplete(Block frame) {
        // The frame can be any of the 12 around a portal; try every centre it could belong to.
        for (BlockFace inward : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockFace side = rotate(inward);
            for (int offset = -1; offset <= 1; offset++) {
                Block center = frame.getRelative(inward, 2).getRelative(side, offset);
                if (ringComplete(center, frame)) return true;
            }
        }
        return false;
    }

    /** Checks the 12 frames around a 3x3 portal centred on {@code center}, counting {@code adding} as filled. */
    private static boolean ringComplete(Block center, Block adding) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            BlockFace side = rotate(face);
            for (int offset = -1; offset <= 1; offset++) {
                Block b = center.getRelative(face, 2).getRelative(side, offset);
                if (b.getType() != Material.END_PORTAL_FRAME) return false;
                if (b.equals(adding)) continue;
                if (!(b.getBlockData() instanceof EndPortalFrame f) || !f.hasEye()) return false;
            }
        }
        return true;
    }

    private static BlockFace rotate(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private final java.util.Map<java.util.UUID, Long> lastMessage = new java.util.HashMap<>();

    /**
     * A portal could already exist (e.g. built before this plugin); don't let anyone through it.
     * Stopped as soon as they step in, before the server starts the trip (and builds the End platform).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnterPortal(org.bukkit.event.entity.EntityPortalEnterEvent event) {
        if (event.getPortalType() != org.bukkit.PortalType.ENDER) return;
        World world = event.getLocation().getWorld();
        if (world == null || world.getEnvironment() == World.Environment.THE_END || isOpen()) return;
        if (event.getEntity() instanceof Player p && p.hasPermission("survivalcore.endportal.bypass")) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Player p) tell(p);
    }

    /** Backup in case something gets past the enter event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;
        if (event.getFrom().getWorld() == null || event.getFrom().getWorld().getEnvironment() == World.Environment.THE_END) return;
        if (isOpen() || event.getPlayer().hasPermission("survivalcore.endportal.bypass")) return;
        event.setCancelled(true);
        tell(event.getPlayer());
    }

    /** At most one "closed" message every 3 seconds (portals fire every tick you stand in them). */
    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendMessage(prefix() + ChatColor.RED + closedMessage());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isOpen()) return;
        if (event.getFrom().getWorld() == null || event.getFrom().getWorld().getEnvironment() == World.Environment.THE_END) return;
        if (event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END) {
            event.setCancelled(true);
        }
    }

    private String closedMessage() {
        long opensAt = config().getLong("opens-at", 0);
        String base = config().getString("closed-message", "The End is closed for now.");
        if (opensAt > System.currentTimeMillis()) {
            return base + " It opens in " + format(Duration.ofMillis(opensAt - System.currentTimeMillis())) + ".";
        }
        return base;
    }

    static String format(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long days = s / 86400, hours = (s % 86400) / 3600, minutes = (s % 3600) / 60, seconds = s % 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (parts.isEmpty() || (days == 0 && hours == 0 && minutes < 5 && seconds > 0)) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    /** "2h30m", "1d", "45 m" -> duration, or null. */
    static Duration parseDuration(String text) {
        Matcher m = DURATION.matcher(text.toLowerCase(Locale.ROOT));
        long seconds = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            seconds += switch (m.group(2)) {
                case "d" -> n * 86400;
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return any && seconds > 0 ? Duration.ofSeconds(seconds) : null;
    }

    private final class EndPortalCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "open" -> {
                    if (args.length >= 3 && args[1].equalsIgnoreCase("in")) {
                        Duration d = parseDuration(String.join("", java.util.Arrays.copyOfRange(args, 2, args.length)));
                        if (d == null) {
                            sender.sendMessage(prefix() + ChatColor.RED + "Usage: /" + label + " open in 2h30m");
                            return true;
                        }
                        config().set("open", false);
                        config().set("opens-at", Instant.now().plus(d).toEpochMilli());
                        plugin.saveConfig();
                        scheduleTimer();
                        sender.sendMessage(prefix() + ChatColor.GREEN + "The End will open in " + format(d) + ".");
                    } else {
                        setOpen(true);
                        sender.sendMessage(prefix() + ChatColor.GREEN + "The End is open. Portals can be lit.");
                    }
                }
                case "close" -> {
                    setOpen(false);
                    sender.sendMessage(prefix() + ChatColor.YELLOW + "The End is closed. The 12th eye can't be placed"
                            + " and existing portals won't work.");
                }
                default -> {
                    long opensAt = config().getLong("opens-at", 0);
                    if (isOpen()) {
                        sender.sendMessage(prefix() + ChatColor.GREEN + "The End is open.");
                    } else if (opensAt > 0) {
                        sender.sendMessage(prefix() + ChatColor.YELLOW + "The End is closed. Opens in "
                                + format(Duration.ofMillis(opensAt - System.currentTimeMillis())) + ".");
                    } else {
                        sender.sendMessage(prefix() + ChatColor.YELLOW + "The End is closed.");
                    }
                    sender.sendMessage(ChatColor.GRAY + "/" + label + " open | open in <time> | close");
                }
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> options = new ArrayList<>();
            if (args.length == 1) options.addAll(List.of("open", "close", "status"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("open")) options.add("in");
            else if (args.length == 3 && args[0].equalsIgnoreCase("open")) options.addAll(List.of("30m", "1h", "12h", "1d"));
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.startsWith(last));
            return options;
        }
    }
}

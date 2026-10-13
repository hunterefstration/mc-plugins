package dev.survivalcore.ores;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import dev.survivalcore.util.PlacedBlocks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hidden stone, deepslate and netherrack near players sometimes turns into ore. Only blocks that
 * are completely enclosed (no air, water or see-through block touching any side) and natural
 * (never placed by a player or made by a cobblestone/stone generator) can change, so nobody sees it
 * happen and builds are never touched. Each chunk only gets a limited number of new veins.
 */
public final class OreGrowthModule extends Module {

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};
    private static final Set<Material> HOSTS = Set.of(Material.STONE, Material.DEEPSLATE, Material.NETHERRACK);

    private final PlacedBlocks placed;
    private final NamespacedKey grownKey;
    private final Map<Material, Map<Material, Integer>> weights = new EnumMap<>(Material.class);
    private BukkitTask task;
    private double chance;
    private int samples;
    private int minRadius;
    private int maxRadius;
    private int perChunk;
    private int minVein;
    private int maxVein;
    private int overworldMaxY;
    private Set<String> disabledWorlds = new HashSet<>();

    public OreGrowthModule(SurvivalCore plugin) {
        super(plugin, "ore-growth", "OreGrowth");
        this.placed = new PlacedBlocks(new NamespacedKey(plugin, "placed_hosts"));
        this.grownKey = new NamespacedKey(plugin, "grown_veins");
    }

    @Override
    public void enable() {
        chance = Math.max(0, Math.min(1, config().getDouble("chance", 0.01)));
        samples = Math.max(1, config().getInt("samples-per-player", 8));
        minRadius = Math.max(0, config().getInt("min-distance", 6));
        maxRadius = Math.max(minRadius + 1, config().getInt("max-distance", 24));
        perChunk = Math.max(0, config().getInt("max-veins-per-chunk", 4));
        minVein = Math.max(1, config().getInt("vein-size.min", 1));
        maxVein = Math.max(minVein, config().getInt("vein-size.max", 4));
        disabledWorlds = new HashSet<>(config().getStringList("disabled-worlds"));
        overworldMaxY = config().getInt("overworld-max-y", 40);

        weights.clear();
        loadWeights(Material.STONE, "ores.stone");
        loadWeights(Material.DEEPSLATE, "ores.deepslate");
        loadWeights(Material.NETHERRACK, "ores.netherrack");

        long interval = Math.max(1, config().getLong("interval-ticks", 40));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        task = null;
    }

    private void loadWeights(Material host, String path) {
        Map<Material, Integer> map = new LinkedHashMap<>();
        ConfigurationSection s = config().getConfigurationSection(path);
        if (s != null) {
            for (String key : s.getKeys(false)) {
                Material ore = Material.matchMaterial(key);
                int w = s.getInt(key);
                if (ore == null || !ore.isBlock()) {
                    plugin.getLogger().warning("ore-growth." + path + ": unknown block '" + key + "'");
                } else if (w > 0) {
                    map.put(ore, w);
                }
            }
        }
        weights.put(host, map);
    }

    // ------------------------------------------------------------------ growing

    private void tick() {
        if (chance <= 0) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            World world = player.getWorld();
            if (disabledWorlds.contains(world.getName()) || world.getEnvironment() == World.Environment.THE_END) continue;
            Location at = player.getLocation();
            for (int i = 0; i < samples; i++) {
                if (rnd.nextDouble() >= chance) continue; // decide first: cheap, and keeps the rate exact
                Block block = randomBlockAround(world, at, rnd);
                if (block != null) tryGrow(block, rnd);
            }
        }
    }

    private Block randomBlockAround(World world, Location at, ThreadLocalRandom rnd) {
        for (int attempt = 0; attempt < 4; attempt++) {
            int dx = rnd.nextInt(-maxRadius, maxRadius + 1);
            int dy = rnd.nextInt(-maxRadius, maxRadius + 1);
            int dz = rnd.nextInt(-maxRadius, maxRadius + 1);
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > maxRadius * maxRadius || d2 < minRadius * minRadius) continue;
            int x = at.getBlockX() + dx, y = at.getBlockY() + dy, z = at.getBlockZ() + dz;
            if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) continue;
            // Keep it underground in the overworld, away from where people build.
            if (world.getEnvironment() == World.Environment.NORMAL && y > overworldMaxY) continue;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = world.getBlockAt(x, y, z);
            if (HOSTS.contains(b.getType())) return b;
        }
        return null;
    }

    private void tryGrow(Block origin, ThreadLocalRandom rnd) {
        Material host = origin.getType();
        if (!isHiddenNatural(origin, host)) return;
        if (grownIn(origin.getChunk()) >= perChunk) return;

        Material ore = pickOre(host, origin.getY(), rnd);
        if (ore == null) return;
        int size = Math.min(maxSizeFor(ore), rnd.nextInt(minVein, maxVein + 1));

        // Grow a small connected vein through hidden, natural blocks of the same kind.
        List<Block> vein = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && vein.size() < size) {
            Block b = queue.poll();
            if (b != origin && (b.getType() != host || !isHiddenNatural(b, host) || !heightOk(b, ore))) continue;
            vein.add(b);
            List<BlockFace> faces = new ArrayList<>(Arrays.asList(FACES));
            java.util.Collections.shuffle(faces, rnd);
            for (BlockFace f : faces) {
                Block n = b.getRelative(f);
                if (seen.add(n) && n.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) queue.add(n);
            }
        }
        // Every chunk the vein reaches into counts it against its limit.
        Set<Chunk> chunks = new HashSet<>();
        for (Block b : vein) chunks.add(b.getChunk());
        for (Chunk c : chunks) if (grownIn(c) >= perChunk) return;
        for (Block b : vein) b.setType(ore, false);
        for (Chunk c : chunks) c.getPersistentDataContainer().set(grownKey, PersistentDataType.INTEGER, grownIn(c) + 1);
    }

    private int grownIn(Chunk chunk) {
        return chunk.getPersistentDataContainer().getOrDefault(grownKey, PersistentDataType.INTEGER, 0);
    }

    /** Every block of a vein stays within the height limits, not just the first. */
    private boolean heightOk(Block b, Material ore) {
        if (b.getWorld().getEnvironment() == World.Environment.NORMAL && b.getY() > overworldMaxY) return false;
        return fitsHeight(ore, b.getY());
    }

    /** Every side touches a solid, opaque block, and no player put it there. */
    private boolean isHiddenNatural(Block block, Material host) {
        if (block.getType() != host) return false;
        for (BlockFace f : FACES) {
            Block n = block.getRelative(f);
            if (!n.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) return false;
            Material t = n.getType();
            if (!t.isOccluding() || t.isAir()) return false;
        }
        return !isPlaced(block);
    }

    private Material pickOre(Material host, int y, ThreadLocalRandom rnd) {
        Map<Material, Integer> table = weights.get(host);
        if (table == null || table.isEmpty()) return null;
        Map<Material, Integer> usable = new LinkedHashMap<>();
        for (Map.Entry<Material, Integer> e : table.entrySet()) {
            if (fitsHeight(e.getKey(), y)) usable.put(e.getKey(), e.getValue());
        }
        int total = usable.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return null;
        int roll = rnd.nextInt(total);
        for (Map.Entry<Material, Integer> e : usable.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return null;
    }

    /** Keeps ores roughly where vanilla puts them (no diamonds near the surface). */
    private static boolean fitsHeight(Material ore, int y) {
        return switch (ore) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> y <= 16;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE -> y <= 32;
            case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> y <= 64;
            case EMERALD_ORE -> y >= 0;
            case ANCIENT_DEBRIS -> y >= 8 && y <= 119;
            default -> true;
        };
    }

    private static int maxSizeFor(Material ore) {
        return switch (ore) {
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE, ANCIENT_DEBRIS -> 1;
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    // ------------------------------------------------------------------ remembering placed blocks

    private boolean isPlaced(Block block) {
        return placed.contains(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (HOSTS.contains(event.getBlockPlaced().getType())) placed.set(event.getBlockPlaced(), true);
    }

    /** Stone made by lava and water (generators) isn't natural either. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (HOSTS.contains(event.getNewState().getType())) placed.set(event.getBlock(), true);
    }

    /** Placed stone moved by pistons stays "placed" at its new spot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        movePlaced(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        movePlaced(event.getBlocks(), event.getDirection());
    }

    private void movePlaced(List<Block> blocks, BlockFace direction) {
        List<Block> moving = new ArrayList<>();
        for (Block b : blocks) if (HOSTS.contains(b.getType()) && isPlaced(b)) moving.add(b);
        for (Block b : moving) placed.set(b, false);
        for (Block b : moving) placed.set(b.getRelative(direction), true);
    }

    // Forget placed blocks that are gone, however they went, so the records don't pile up.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        for (Block b : event.blockList()) forget(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        for (Block b : event.blockList()) forget(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (event.getTo() != event.getBlock().getType()) forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        forget(event.getBlock());
    }

    private void forget(Block b) {
        if (HOSTS.contains(b.getType())) placed.set(b, false);
    }
}

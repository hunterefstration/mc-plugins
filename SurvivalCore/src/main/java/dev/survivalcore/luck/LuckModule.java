package dev.survivalcore.luck;

import dev.survivalcore.Module;
import dev.survivalcore.SurvivalCore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.loot.Lootable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Hidden luck boost. Ops give a player a boost level with /luckboost; nobody else can see the
 * command, it isn't listed in /sc, and the player is never told.
 *
 * <p>With boost level N, every loot roll that player causes is rolled N extra times with N more
 * luck, and they get the best result. This covers loot chests, barrels and minecart chests, trial
 * vaults, trial spawner rewards, fishing, mob drops, block drops (ores, leaves, gravel...) and
 * piglin bartering. It also adds N to their Luck attribute, which vanilla uses for anything
 * luck-based. Only the loot itself is ever swapped: anything else in a drop is left alone.
 */
public final class LuckModule extends Module {

    private static final int MAX_LEVEL = 50;
    /**
     * Chest loot that creates explorer maps: every roll would search for a structure and save a
     * new map, so these are never re-rolled.
     */
    private static final Set<String> MAP_TABLES = Set.of("chests/shipwreck_map", "chests/underwater_ruin_big",
            "chests/underwater_ruin_small");

    private final NamespacedKey modifierKey;
    private final File file;
    private final Map<UUID, Integer> levels = new HashMap<>();
    private final Random random = new Random();
    /**
     * Taken just before a boosted player breaks a block: its neighbours (to tell popped-off items
     * from the block's own drops) and the tool as it was before mining wore it down.
     */
    private record BreakSnapshot(Map<Block, BlockState> neighbours, ItemStack tool) {}
    private final Map<Block, BreakSnapshot> neighbours = new HashMap<>();

    public LuckModule(SurvivalCore plugin) {
        super(plugin, "luck-boost", "LuckBoost");
        this.modifierKey = new NamespacedKey(plugin, "lb");
        this.file = new File(plugin.getDataFolder(), "luck.yml");
        plugin.bind("luckboost", this, new LuckCommand());
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public void enable() {
        levels.clear();
        neighbours.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        var section = data.getConfigurationSection("players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int level = section.getInt(key);
                    if (level > 0) levels.put(UUID.fromString(key), Math.min(MAX_LEVEL, level));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) applyAttribute(p);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) removeAttribute(p);
        neighbours.clear();
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        levels.forEach((id, level) -> data.set("players." + id, level));
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save luck.yml", e);
        }
    }

    public int level(Entity entity) {
        if (!(entity instanceof Player p)) return 0;
        return levels.getOrDefault(p.getUniqueId(), 0);
    }

    // ------------------------------------------------------------------ Luck attribute

    private void applyAttribute(Player player) {
        removeAttribute(player);
        int level = level(player);
        AttributeInstance luck = player.getAttribute(Attribute.LUCK);
        if (level <= 0 || luck == null) return;
        // Transient: never saved into the player's data, so nothing lingers if the boost is removed.
        luck.addTransientModifier(new AttributeModifier(modifierKey, level, AttributeModifier.Operation.ADD_NUMBER));
    }

    private void removeAttribute(Player player) {
        AttributeInstance luck = player.getAttribute(Attribute.LUCK);
        if (luck != null) luck.removeModifier(modifierKey);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyAttribute(event.getPlayer());
    }

    // ------------------------------------------------------------------ best of several rolls

    /** Rolls the table {@code level} times (stops early if the table can't be rolled from here). */
    private List<Collection<ItemStack>> rolls(int level, Supplier<Collection<ItemStack>> roll) {
        List<Collection<ItemStack>> rolls = new ArrayList<>();
        for (int i = 0; i < level; i++) {
            try {
                rolls.add(roll.get());
            } catch (Throwable t) {
                break;
            }
        }
        return rolls;
    }

    /** The best of {@code rolls} if it beats {@code original}, else null. */
    private static List<ItemStack> pick(Collection<ItemStack> original, List<Collection<ItemStack>> rolls) {
        List<ItemStack> best = null;
        double bestScore = LootValue.of(original);
        for (Collection<ItemStack> r : rolls) {
            double score = LootValue.of(r);
            if (score > bestScore) {
                bestScore = score;
                best = new ArrayList<>(r);
            }
        }
        return best;
    }

    private List<ItemStack> best(Collection<ItemStack> original, int level, Supplier<Collection<ItemStack>> roll) {
        return pick(original, rolls(level, roll));
    }

    /** Every item type any of the rolls produced. */
    private static Set<Material> types(List<Collection<ItemStack>> rolls) {
        Set<Material> types = EnumSet.noneOf(Material.class);
        for (Collection<ItemStack> r : rolls) for (ItemStack i : r) if (i != null) types.add(i.getType());
        return types;
    }

    /** The same luck rule everywhere: the player's own luck (without our bonus) plus the boost level. */
    private static float luckFor(Player player, int level) {
        AttributeInstance luck = player.getAttribute(Attribute.LUCK);
        float own = luck == null ? 0 : (float) luck.getValue() - level;
        return own + level;
    }

    private LootContext context(Location at, Player player, int level, Entity looted, float extraLuck) {
        return new LootContext.Builder(at)
                .luck(luckFor(player, level) + extraLuck)
                .lootedEntity(looted)
                .killer(player)
                .build();
    }

    /** The boosted player nearest to a spot (for loot that isn't tied to one player). */
    private Player nearestBoosted(Location at, double radius) {
        Player found = null;
        double bestDist = radius * radius;
        for (Player p : at.getWorld().getPlayers()) {
            if (level(p) <= 0) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d <= bestDist) {
                bestDist = d;
                found = p;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ loot chests, barrels, minecart chests

    /** LOWEST: swap the vanilla loot before other plugins add their own items. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onContainerLoot(LootGenerateEvent event) {
        if (event.isPlugin()) return;
        Player player = event.getEntity() instanceof Player p ? p : null;
        int level = level(player);
        if (level <= 0) return;
        LootTable table = event.getLootTable();
        if (MAP_TABLES.contains(table.getKey().getKey())) return;
        LootContext base = event.getLootContext();
        LootContext ctx = context(base.getLocation(), player, level, base.getLootedEntity(), 0);
        List<ItemStack> best = best(event.getLoot(), level, () -> table.populateLoot(random, ctx));
        if (best != null) event.setLoot(best);
    }

    // ------------------------------------------------------------------ trial vaults and trial spawners

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispenseLoot(BlockDispenseLootEvent event) {
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        Player player = event.getPlayer() != null ? event.getPlayer() : nearestBoosted(at, 16);
        int level = level(player);
        if (level <= 0) return;
        LootTable table = event.getLootTable();
        LootContext ctx = context(at, player, level, player, 0);
        List<ItemStack> best = best(event.getDispensedLoot(), level, () -> table.populateLoot(random, ctx));
        if (best != null) event.setDispensedLoot(best);
    }

    // ------------------------------------------------------------------ fishing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        Player player = event.getPlayer();
        int level = level(player);
        if (level <= 0) return;
        FishHook hook = event.getHook();
        ItemStack rod = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        LootTable table = LootTables.FISHING.getLootTable();
        LootContext ctx = context(hook.getLocation(), player, level, hook, rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA));
        List<ItemStack> best = best(List.of(caught.getItemStack()), level, () -> table.populateLoot(random, ctx));
        if (best == null || best.isEmpty()) return;
        caught.setItemStack(best.get(0));
        for (int i = 1; i < best.size(); i++) {
            // Thrown towards the player the same way vanilla throws the catch.
            Item extra = caught.getWorld().dropItem(caught.getLocation(), best.get(i));
            Vector d = player.getLocation().toVector().subtract(caught.getLocation().toVector());
            extra.setVelocity(new Vector(d.getX() * 0.1, d.getY() * 0.1 + Math.sqrt(d.length()) * 0.08, d.getZ() * 0.1));
        }
    }

    // ------------------------------------------------------------------ mob drops

    /** LOWEST: before other plugins add drops, so only the vanilla loot is ever swapped. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player || dead instanceof InventoryHolder || !(dead instanceof Mob)) return;
        Player killer = dead.getKiller();
        int level = level(killer);
        if (level <= 0 || !(dead instanceof Lootable lootable) || lootable.getLootTable() == null) return;
        // Same rules as vanilla for whether the loot table drops anything at all.
        if (Boolean.FALSE.equals(dead.getWorld().getGameRuleValue(GameRule.DO_MOB_LOOT))) return;
        if (dead instanceof Ageable a && !a.isAdult() && !(dead instanceof Monster)) return;
        // Killed by a charged creeper: the skull it drops isn't loot-table loot; leave it all alone.
        if (dead.getLastDamageCause() instanceof EntityDamageByEntityEvent hit
                && hit.getDamager() instanceof Creeper creeper && creeper.isPowered()) return;

        // Split what dropped into loot-table loot and everything else: worn/held items (each slot
        // matches at most one drop) and an enderman's carried block.
        List<ItemStack> kept = new ArrayList<>();
        List<ItemStack> fromTable = new ArrayList<>(event.getDrops());
        EntityEquipment equipment = dead.getEquipment();
        if (equipment != null) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack worn;
                try {
                    worn = equipment.getItem(slot);
                } catch (IllegalArgumentException e) {
                    continue; // slot not supported by this mob
                }
                if (worn == null || worn.getType().isAir()) continue;
                takeFirst(fromTable, worn, kept);
            }
        }
        if (dead instanceof Enderman enderman && enderman.getCarriedBlock() != null) {
            takeFirst(fromTable, new ItemStack(enderman.getCarriedBlock().getMaterial()), kept);
        }

        LootTable table = lootable.getLootTable();
        LootContext ctx = context(dead.getLocation(), killer, level, dead, 0);
        List<ItemStack> best = best(fromTable, level, () -> table.populateLoot(random, ctx));
        if (best == null) return;
        event.getDrops().clear();
        event.getDrops().addAll(best);
        event.getDrops().addAll(kept);
    }

    /** Moves the first drop that is the same item as {@code like} from {@code from} to {@code to}. */
    private static void takeFirst(List<ItemStack> from, ItemStack like, List<ItemStack> to) {
        for (int i = 0; i < from.size(); i++) {
            if (from.get(i).isSimilar(like)) {
                to.add(from.remove(i));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ block drops

    /** Remember the neighbours, so items that pop off them (torches, rails...) aren't mistaken for loot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (level(event.getPlayer()) <= 0) return;
        Map<Block, BlockState> around = new HashMap<>();
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            Block n = event.getBlock().getRelative(f);
            if (!n.getType().isAir()) around.put(n, n.getState());
        }
        neighbours.put(event.getBlock(), new BreakSnapshot(around, event.getPlayer().getInventory().getItemInMainHand().clone()));
        // Normally used straight away by the drop event; never kept past this tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> neighbours.remove(event.getBlock()));
    }

    /** LOWEST: before other plugins add their own drops, which must never be swapped away. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        BreakSnapshot snapshot = neighbours.remove(event.getBlock());
        Map<Block, BlockState> around = snapshot == null ? null : snapshot.neighbours();
        Player player = event.getPlayer();
        int level = level(player);
        if (level <= 0 || event.getItems().isEmpty()) return;
        BlockState state = event.getBlockState();
        // Containers drop what's inside them; never touch that.
        if (state instanceof InventoryHolder || state instanceof ShulkerBox) return;
        // Roll with the tool as it was when the block broke (mining may have used up its last durability).
        ItemStack tool = snapshot != null ? snapshot.tool() : player.getInventory().getItemInMainHand();

        List<Collection<ItemStack>> rolls = rolls(level, () -> state.getDrops(tool, player));
        Set<Material> rollTypes = types(rolls);
        // What popped off neighbours that broke along with it (a torch on the ore, a rail on top...).
        boolean somethingPopped = false;
        if (around != null) {
            for (Map.Entry<Block, BlockState> e : around.entrySet()) {
                if (e.getKey().getType() != e.getValue().getType()) somethingPopped = true;
            }
        }
        List<Item> own = new ArrayList<>();
        List<ItemStack> original = new ArrayList<>();
        for (Item item : event.getItems()) {
            Material type = item.getItemStack().getType();
            // If something broke along with it, only item types this block can drop are its own;
            // anything else came off the neighbour (however lucky its roll was) and is left alone.
            if (somethingPopped && !rollTypes.contains(type)) continue;
            own.add(item);
            original.add(item.getItemStack());
        }
        if (own.isEmpty()) return;
        List<ItemStack> best = pick(original, rolls);
        if (best == null || best.isEmpty()) return;

        int i = 0;
        for (; i < best.size() && i < own.size(); i++) own.get(i).setItemStack(best.get(i));
        for (int j = i; j < own.size(); j++) event.getItems().remove(own.get(j));
        // Extra stacks join the event's list, so they only spawn if the drop isn't cancelled
        // (and other plugins, like VeinMiner's gathering, see them too).
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (; i < best.size(); i++) {
            Item extra = at.getWorld().createEntity(at, Item.class);
            extra.setItemStack(best.get(i));
            event.getItems().add(extra);
        }
    }

    // ------------------------------------------------------------------ piglin bartering

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent event) {
        Player player = nearestBoosted(event.getEntity().getLocation(), 12);
        int level = level(player);
        if (level <= 0) return;
        LootTable table = LootTables.PIGLIN_BARTERING.getLootTable();
        LootContext ctx = context(event.getEntity().getLocation(), player, level, event.getEntity(), 0);
        List<ItemStack> best = best(event.getOutcome(), level, () -> table.populateLoot(random, ctx));
        if (best == null) return;
        event.getOutcome().clear();
        event.getOutcome().addAll(best);
    }

    // ------------------------------------------------------------------ /luckboost

    private final class LuckCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                if (levels.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Nobody has a luck boost.");
                levels.forEach((id, level) -> sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.WHITE
                        + nameOf(id) + ChatColor.GRAY + ": level " + ChatColor.GREEN + level));
                sender.sendMessage(ChatColor.DARK_GRAY + "/" + label + " <player> <level 1-" + MAX_LEVEL + "|off>");
                return true;
            }
            // Only players this server knows: never a blocking lookup on Mojang's servers for a typo.
            OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                sender.sendMessage(ChatColor.RED + args[0] + " has never joined this server.");
                return true;
            }
            UUID id = target.getUniqueId();
            if (args.length == 1) {
                int level = levels.getOrDefault(id, 0);
                sender.sendMessage(ChatColor.GRAY + nameOf(id) + ": " + (level > 0 ? "level " + level : "no boost"));
                return true;
            }
            int level;
            if (args[1].equalsIgnoreCase("off") || args[1].equals("0")) {
                level = 0;
            } else {
                try {
                    level = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Level must be a number from 1 to " + MAX_LEVEL + ", or off.");
                    return true;
                }
                if (level < 1 || level > MAX_LEVEL) {
                    sender.sendMessage(ChatColor.RED + "Level must be from 1 to " + MAX_LEVEL + ".");
                    return true;
                }
            }
            if (level == 0) levels.remove(id);
            else levels.put(id, level);
            save();
            Player online = target.getPlayer();
            if (online != null) applyAttribute(online);
            sender.sendMessage(ChatColor.GRAY + nameOf(id) + ": " + (level > 0
                    ? "luck boost set to level " + ChatColor.GREEN + level + ChatColor.GRAY + " (best of " + (level + 1) + " rolls)."
                    : "luck boost removed."));
            return true;
        }

        private String nameOf(UUID id) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name != null ? name : id.toString();
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> options = new ArrayList<>();
            if (args.length == 1) {
                options.add("list");
                Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            } else if (args.length == 2) {
                options.addAll(List.of("1", "3", "5", "10", "off"));
            }
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
            return options;
        }
    }
}

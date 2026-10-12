package dev.effectbooks;

import dev.effectbooks.model.Book;
import dev.effectbooks.util.Effects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Owns all book state. The data here is the source of truth: the physical book item is only a
 * view of it that gets regenerated whenever it goes missing, so it can never really be destroyed.
 */
public final class BookManager {

    private static final int NAMES_PER_PAGE = 13;

    private final EffectBooksPlugin plugin;
    private final NamespacedKey bookKey;
    private final NamespacedKey managedKey;
    private final File dataFile;

    /** Books listed in config.yml. */
    private final Map<UUID, Book> books = new LinkedHashMap<>();
    /** Books whose config line failed to parse; kept untouched so a typo doesn't wipe their names. */
    private final Map<UUID, Book> dormant = new LinkedHashMap<>();

    private boolean refreshQueued;
    private final Set<UUID> warnedFullInventory = new HashSet<>();

    public BookManager(EffectBooksPlugin plugin) {
        this.plugin = plugin;
        this.bookKey = new NamespacedKey(plugin, "book_id");
        this.managedKey = new NamespacedKey(plugin, "managed_effects");
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------ loading / saving

    /** Loads config.yml (which books exist) and data.yml (who is written in them). */
    public void load() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        List<Book> stored = new ArrayList<>();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection dataBooks = data.getConfigurationSection("books");
        if (dataBooks != null) {
            for (String key : dataBooks.getKeys(false)) {
                ConfigurationSection s = dataBooks.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    PotionEffectType effect = Effects.parse(s.getString("effect"));
                    String holder = s.getString("holder");
                    if (effect == null || holder == null) continue;
                    Book book = new Book(UUID.fromString(key), holder, effect, clampLevel(s.getInt("level", 1)));
                    book.targets().addAll(s.getStringList("targets"));
                    stored.add(book);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping corrupt book entry '" + key + "' in data.yml");
                }
            }
        }

        books.clear();
        dormant.clear();
        boolean configErrors = false;

        ConfigurationSection section = config.getConfigurationSection("books");
        if (section != null) {
            for (String holder : section.getKeys(false)) {
                if (!Effects.isValidName(holder)) {
                    plugin.getLogger().warning("config.yml: '" + holder + "' is not a valid username, skipping.");
                    configErrors = true;
                    continue;
                }
                List<String> specs = section.isList(holder)
                        ? section.getStringList(holder)
                        : List.of(String.valueOf(section.get(holder)));
                for (String spec : specs) {
                    ParsedSpec parsed = parseSpec(spec);
                    if (parsed == null) {
                        plugin.getLogger().warning("config.yml: unknown effect '" + spec + "' for " + holder
                                + ". Books for this line are kept but inactive until it is fixed.");
                        configErrors = true;
                        continue;
                    }
                    if (find(holder, parsed.effect) != null) {
                        plugin.getLogger().warning("config.yml: " + holder + " is listed twice with "
                                + parsed.effect.getKey() + ", ignoring the duplicate.");
                        continue;
                    }
                    Book match = null;
                    for (Book b : stored) {
                        if (b.isHolder(holder) && b.effect().equals(parsed.effect)) {
                            match = b;
                            break;
                        }
                    }
                    Book book;
                    if (match != null) {
                        stored.remove(match);
                        book = match;
                        book.setHolder(holder);
                        book.setLevel(parsed.level);
                    } else {
                        book = new Book(UUID.randomUUID(), holder, parsed.effect, parsed.level);
                    }
                    books.put(book.id(), book);
                }
            }
        }

        for (Book leftover : stored) {
            if (configErrors) {
                dormant.put(leftover.id(), leftover);
            } else {
                plugin.getLogger().info("Removed " + leftover.displayName() + " (" + leftover.holder()
                        + ") because it is no longer in config.yml; its effects will be cleared.");
            }
        }
        if (!dormant.isEmpty()) {
            plugin.getLogger().warning(dormant.size() + " book(s) are inactive until config.yml errors are fixed.");
        }
        saveData();
    }

    public void saveData() {
        YamlConfiguration data = new YamlConfiguration();
        List<Book> all = new ArrayList<>(books.values());
        all.addAll(dormant.values());
        for (Book b : all) {
            String path = "books." + b.id();
            data.set(path + ".holder", b.holder());
            data.set(path + ".effect", Effects.id(b.effect()));
            data.set(path + ".level", b.level());
            data.set(path + ".targets", new ArrayList<>(b.targets()));
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    /** Writes the current books back to config.yml, keeping the file's comments. */
    public void saveConfig() {
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("books");
        if (section == null) {
            section = config.createSection("books");
        } else {
            for (String key : section.getKeys(false)) section.set(key, null);
        }
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Book b : sortedBooks()) {
            grouped.computeIfAbsent(b.holder(), k -> new ArrayList<>()).add(b.spec());
        }
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            section.set(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0) : e.getValue());
        }
        plugin.saveConfig();
    }

    private void persist() {
        saveConfig();
        saveData();
        queueRefresh();
    }

    // ------------------------------------------------------------------ queries

    public Collection<Book> books() {
        return books.values();
    }

    public List<Book> sortedBooks() {
        List<Book> list = new ArrayList<>(books.values());
        list.sort(Comparator.comparing((Book b) -> b.holder().toLowerCase(Locale.ROOT))
                .thenComparing(b -> Effects.prettyName(b.effect())));
        return list;
    }

    public Book get(UUID id) {
        return id == null ? null : books.get(id);
    }

    public Book find(String holder, PotionEffectType effect) {
        for (Book b : books.values()) {
            if (b.isHolder(holder) && b.effect().equals(effect)) return b;
        }
        return null;
    }

    /** effect -> amplifier this player should permanently have (highest level wins). */
    public Map<PotionEffectType, Integer> desiredEffects(String playerName) {
        Map<PotionEffectType, Integer> result = new HashMap<>();
        for (Book b : books.values()) {
            if (b.hasTarget(playerName)) result.merge(b.effect(), b.amplifier(), Math::max);
        }
        return result;
    }

    // ------------------------------------------------------------------ mutations (all save + resync)

    public Book create(String holder, PotionEffectType effect, int level) {
        requireValid(holder, effect);
        if (find(holder, effect) != null) {
            throw new IllegalArgumentException(holder + " already has a Book of " + Effects.prettyName(effect) + ".");
        }
        Book book = new Book(UUID.randomUUID(), holder, effect, clampLevel(level));
        books.put(book.id(), book);
        persist();
        return book;
    }

    /** Deleting a book clears its effect from everyone written in it. */
    public void delete(Book book) {
        books.remove(book.id());
        persist();
    }

    /** Moves the book (and everyone written in it) to a new holder. */
    public void reassign(Book book, String newHolder) {
        requireValid(newHolder, book.effect());
        Book existing = find(newHolder, book.effect());
        if (existing != null && existing != book) {
            throw new IllegalArgumentException(newHolder + " already has a Book of "
                    + Effects.prettyName(book.effect()) + ".");
        }
        book.setHolder(newHolder);
        persist();
    }

    public void changeEffect(Book book, PotionEffectType effect) {
        requireValid(book.holder(), effect);
        Book existing = find(book.holder(), effect);
        if (existing != null && existing != book) {
            throw new IllegalArgumentException(book.holder() + " already has a Book of "
                    + Effects.prettyName(effect) + ".");
        }
        book.setEffect(effect);
        persist();
    }

    public void setLevel(Book book, int level) {
        book.setLevel(clampLevel(level));
        persist();
    }

    public void setTargets(Book book, List<String> names) {
        book.targets().clear();
        book.targets().addAll(names);
        saveData();
        queueRefresh();
    }

    public boolean addTarget(Book book, String name) {
        if (!Effects.isValidName(name)) throw new IllegalArgumentException("'" + name + "' is not a valid username.");
        if (book.hasTarget(name)) return false;
        book.targets().add(name);
        saveData();
        queueRefresh();
        return true;
    }

    public void removeTarget(Book book, String name) {
        book.targets().removeIf(t -> t.equalsIgnoreCase(name));
        saveData();
        queueRefresh();
    }

    private void requireValid(String holder, PotionEffectType effect) {
        if (!Effects.isValidName(holder)) {
            throw new IllegalArgumentException("'" + holder + "' is not a valid username.");
        }
    }

    // ------------------------------------------------------------------ book item

    public ItemStack createItem(Book book) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setItemName(ChatColor.LIGHT_PURPLE + book.displayName());
        meta.setEnchantmentGlintOverride(true);
        meta.setLore(List.of(
                ChatColor.GRAY + "Holder: " + ChatColor.WHITE + book.holder(),
                ChatColor.GRAY + "Write one player name per line.",
                book.effect().isInstant()
                        ? ChatColor.GRAY + "Everyone listed gets " + ChatColor.AQUA + effectLabel(book)
                                + ChatColor.GRAY + " every few seconds."
                        : ChatColor.GRAY + "Everyone listed has " + ChatColor.AQUA + effectLabel(book)
                                + ChatColor.GRAY + " forever."));
        meta.setPages(pagesFor(book.targets()));
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, book.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    private static String effectLabel(Book book) {
        return Effects.prettyName(book.effect()) + (book.level() > 1 ? " " + Effects.roman(book.level()) : "");
    }

    private static List<String> pagesFor(List<String> names) {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < names.size(); i += NAMES_PER_PAGE) {
            pages.add(String.join("\n", names.subList(i, Math.min(names.size(), i + NAMES_PER_PAGE))));
        }
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    /** Result of reading the names out of an edited book. */
    public record ParsedPages(List<String> names, List<String> invalid) {}

    public static ParsedPages parsePages(BookMeta meta) {
        List<String> names = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String page : meta.getPages()) {
            for (String line : page.split("\n")) {
                String name = ChatColor.stripColor(line).trim();
                if (name.isEmpty()) continue;
                if (!Effects.isValidName(name)) {
                    invalid.add(name);
                } else if (seen.add(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            }
        }
        return new ParsedPages(names, invalid);
    }

    public UUID bookId(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK || !item.hasItemMeta()) return null;
        return bookId(item.getItemMeta());
    }

    public UUID bookId(ItemMeta meta) {
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isEffectBook(ItemStack item) {
        return bookId(item) != null;
    }

    // ------------------------------------------------------------------ syncing players

    /** Resync everyone on the next tick (coalesces many changes into one pass). */
    public void queueRefresh() {
        if (refreshQueued) return;
        refreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshQueued = false;
            refreshAll();
        });
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void refresh(Player player) {
        if (!player.isOnline()) return;
        syncBooks(player);
        reconcileEffects(player);
    }

    /**
     * Makes sure the player holds exactly the books they own, up to date,
     * and removes any copies or books that belong to someone else.
     */
    public void syncBooks(Player player) {
        Map<UUID, Book> owed = new LinkedHashMap<>();
        for (Book b : books.values()) {
            if (b.isHolder(player.getName())) owed.put(b.id(), b);
        }
        Set<UUID> found = new HashSet<>();

        // A book on the cursor (mid-drag in an inventory) still counts as held.
        ItemStack cursor = player.getItemOnCursor();
        UUID cursorId = bookId(cursor);
        if (cursorId != null) {
            if (owed.containsKey(cursorId)) {
                found.add(cursorId);
            } else {
                player.setItemOnCursor(null);
            }
        }

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            UUID id = bookId(contents[slot]);
            if (id == null) continue;
            Book book = owed.get(id);
            if (book == null || !found.add(id)) {
                inv.setItem(slot, null); // stale, someone else's, or a duplicate
                continue;
            }
            ItemStack expected = createItem(book);
            if (!expected.isSimilar(contents[slot])) inv.setItem(slot, expected);
        }

        // Books should never sit in the 2x2 crafting grid either.
        InventoryView view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        if (top != null && top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (int slot = 0; slot < top.getSize(); slot++) {
                if (isEffectBook(top.getItem(slot))) top.setItem(slot, null);
            }
        }

        boolean full = false;
        for (Book book : owed.values()) {
            if (found.contains(book.id())) continue;
            if (!inv.addItem(createItem(book)).isEmpty()) full = true;
        }
        if (full) {
            if (warnedFullInventory.add(player.getUniqueId())) {
                player.sendMessage(plugin.prefix() + ChatColor.RED
                        + "Your inventory is full! Free a slot to receive your effect book.");
            }
        } else {
            warnedFullInventory.remove(player.getUniqueId());
        }
    }

    /**
     * Instant effects (Instant Health / Instant Damage) can't last forever, so everyone written in
     * one of those books gets a single dose every check instead.
     */
    public void pulseInstantEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) continue;
            for (Map.Entry<PotionEffectType, Integer> e : desiredEffects(player.getName()).entrySet()) {
                if (e.getKey().isInstant()) {
                    player.addPotionEffect(new PotionEffect(e.getKey(), 1, e.getValue(), true, false, false));
                }
            }
        }
    }

    /** Applies the effects this player should have and removes ones they no longer should. */
    public void reconcileEffects(Player player) {
        Map<PotionEffectType, Integer> desired = desiredEffects(player.getName());
        desired.keySet().removeIf(PotionEffectType::isInstant);
        Map<PotionEffectType, Integer> managed = readManaged(player);

        for (Map.Entry<PotionEffectType, Integer> e : managed.entrySet()) {
            Integer want = desired.get(e.getKey());
            if (want != null && want.equals(e.getValue())) continue;
            PotionEffect current = player.getPotionEffect(e.getKey());
            // Only strip the infinite effect we gave; leave normal potions alone.
            if (current != null && current.isInfinite() && current.getAmplifier() == e.getValue()) {
                player.removePotionEffect(e.getKey());
            }
        }

        boolean particles = plugin.getConfig().getBoolean("show-particles", false);
        boolean icon = plugin.getConfig().getBoolean("show-icon", true);
        for (Map.Entry<PotionEffectType, Integer> e : desired.entrySet()) {
            PotionEffect current = player.getPotionEffect(e.getKey());
            int amp = e.getValue();
            if (current == null || current.getAmplifier() < amp
                    || (current.getAmplifier() == amp && !current.isInfinite())) {
                player.addPotionEffect(new PotionEffect(e.getKey(), PotionEffect.INFINITE_DURATION, amp,
                        true, particles, icon));
            }
        }

        if (!desired.equals(managed)) writeManaged(player, desired);
    }

    private Map<PotionEffectType, Integer> readManaged(Player player) {
        Map<PotionEffectType, Integer> map = new HashMap<>();
        String raw = player.getPersistentDataContainer().get(managedKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return map;
        for (String part : raw.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            PotionEffectType type = Effects.parse(kv[0]);
            try {
                if (type != null) map.put(type, Integer.parseInt(kv[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private void writeManaged(Player player, Map<PotionEffectType, Integer> effects) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (effects.isEmpty()) {
            pdc.remove(managedKey);
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<PotionEffectType, Integer> e : effects.entrySet()) {
            parts.add(Effects.id(e.getKey()) + "=" + e.getValue());
        }
        pdc.set(managedKey, PersistentDataType.STRING, String.join(",", parts));
    }

    // ------------------------------------------------------------------ helpers

    private record ParsedSpec(PotionEffectType effect, int level) {}

    /** "SPEED", "strength:2", "minecraft:haste:3". */
    private static ParsedSpec parseSpec(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        int level = 1;
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            String tail = s.substring(colon + 1).trim();
            if (tail.matches("\\d{1,4}")) {
                level = Integer.parseInt(tail);
                s = s.substring(0, colon);
            }
        }
        PotionEffectType type = Effects.parse(s);
        if (type == null) return null;
        return new ParsedSpec(type, clampLevel(level));
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(Effects.MAX_LEVEL, level));
    }
}

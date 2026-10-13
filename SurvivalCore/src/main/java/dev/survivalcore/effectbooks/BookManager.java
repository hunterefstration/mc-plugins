package dev.survivalcore.effectbooks;

import dev.survivalcore.effectbooks.model.Book;
import dev.survivalcore.effectbooks.util.Effects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
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
    /** A book & quill holds at most 100 pages. */
    public static final int MAX_TARGETS = NAMES_PER_PAGE * 100;

    private final EffectBooksModule plugin;
    private final NamespacedKey bookKey;
    private final NamespacedKey managedKey;
    private final NamespacedKey displayKey;
    /** Keys used by the old standalone EffectBooks plugin. */
    private static final NamespacedKey LEGACY_BOOK_KEY = new NamespacedKey("effectbooks", "book_id");
    private static final NamespacedKey LEGACY_MANAGED_KEY = new NamespacedKey("effectbooks", "managed_effects");
    private final File dataFile;

    /** Books listed in effectbooks.yml. */
    private final Map<UUID, Book> books = new LinkedHashMap<>();
    /** Books whose config line failed to parse; kept untouched so a typo doesn't wipe their names. */
    private final Map<UUID, Book> dormant = new LinkedHashMap<>();

    /** Data entries that couldn't be read (unknown effect...), written back untouched. */
    private final Map<String, Map<String, Object>> badData = new LinkedHashMap<>();

    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            map.put(key, v instanceof ConfigurationSection c ? toMap(c) : v);
        }
        return map;
    }

    /** Lines from effectbooks.yml that didn't parse, kept so rewriting the file never deletes them. */
    private final Map<String, List<String>> badSpecs = new LinkedHashMap<>();
    private final Map<String, Object> badHolders = new LinkedHashMap<>();

    private boolean refreshQueued;
    /** effectbooks.yml or the data file has a YAML error: nothing is saved until it's fixed. */
    private boolean broken;
    /** At least one load worked, so the books in memory are real (not just "nothing loaded yet"). */
    private boolean everLoaded;

    public BookManager(EffectBooksModule plugin) {
        this.plugin = plugin;
        this.bookKey = new NamespacedKey(plugin.core(), "book_id");
        this.managedKey = new NamespacedKey(plugin.core(), "managed_effects");
        this.displayKey = new NamespacedKey(plugin.core(), "book_display");
        this.dataFile = new File(plugin.getDataFolder(), "effectbooks-data.yml");
    }

    // ------------------------------------------------------------------ loading / saving

    /** Loads effectbooks.yml (which books exist) and effectbooks-data.yml (who is written in them). */
    public void load() {
        boolean configOk = plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        YamlConfiguration data = new YamlConfiguration();
        boolean dataOk = true;
        try {
            if (dataFile.exists()) data.load(dataFile);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            plugin.getLogger().severe("effectbooks-data.yml can't be read: " + e.getMessage());
            dataOk = false;
        }
        Object rawBooks = config.get("books", null); // the file's own value, not the default
        boolean booksOk = rawBooks == null || rawBooks instanceof ConfigurationSection;
        if (!booksOk) plugin.getLogger().severe("effectbooks.yml: 'books:' must be a list of holders (check the indentation).");
        RawCheck raw = configOk ? rawCheck() : new RawCheck(false, Set.of());
        if (configOk && !raw.ok()) booksOk = false;
        if (!configOk || !dataOk || !booksOk) {
            // Never treat a broken file as "no books": keep what's loaded and don't save anything.
            broken = true;
            plugin.getLogger().severe("Effect books are frozen until the file is fixed (then run /eb reload). "
                    + "Nothing was deleted.");
            return;
        }
        broken = false;

        List<Book> stored = new ArrayList<>();
        badData.clear();
        ConfigurationSection dataBooks = data.getConfigurationSection("books");
        if (dataBooks != null) {
            for (String key : dataBooks.getKeys(false)) {
                ConfigurationSection s = dataBooks.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    PotionEffectType effect = Effects.parse(s.getString("effect"));
                    String holder = s.getString("holder");
                    if (effect == null || holder == null) {
                        plugin.getLogger().warning("Keeping unreadable book entry '" + key + "' in effectbooks-data.yml as it is.");
                        badData.put(key, toMap(s));
                        continue;
                    }
                    Book book = new Book(UUID.fromString(key), holder, effect, clampLevel(s.getInt("level", 1)));
                    book.targets().addAll(s.getStringList("targets"));
                    stored.add(book);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Keeping unreadable book entry '" + key + "' in effectbooks-data.yml as it is.");
                    badData.put(key, toMap(s));
                }
            }
        }

        books.clear();
        dormant.clear();
        badSpecs.clear();
        badHolders.clear();
        boolean configErrors = false;

        ConfigurationSection section = config.getConfigurationSection("books");
        if (section != null) {
            for (String holder : section.getKeys(false)) {
                if (!Effects.isValidName(holder)) {
                    plugin.getLogger().warning("effectbooks.yml: '" + holder + "' is not a valid username, skipping.");
                    badHolders.put(holder, section.get(holder));
                    configErrors = true;
                    continue;
                }
                Object value = section.get(holder);
                if (!(value instanceof List<?>) && !(value instanceof String)) {
                    plugin.getLogger().warning("effectbooks.yml: '" + holder + "' needs an effect like SPEED or SPEED:2."
                            + " Books for this holder are kept but inactive until it is fixed.");
                    badHolders.put(holder, value);
                    configErrors = true;
                    continue;
                }
                List<String> specs = value instanceof List<?> ? section.getStringList(holder) : List.of((String) value);
                for (String spec : specs) {
                    ParsedSpec parsed = parseSpec(spec);
                    if (parsed == null) {
                        plugin.getLogger().warning("effectbooks.yml: unknown effect '" + spec + "' for " + holder
                                + ". Books for this line are kept but inactive until it is fixed.");
                        badSpecs.computeIfAbsent(holder, k -> new ArrayList<>()).add(spec);
                        configErrors = true;
                        continue;
                    }
                    if (find(holder, parsed.effect) != null) {
                        plugin.getLogger().warning("effectbooks.yml: " + holder + " is listed twice with "
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

        for (String blank : raw.blankHolders()) {
            plugin.getLogger().warning("effectbooks.yml: '" + blank + "' has no effect. Their books are kept but inactive until it is fixed.");
            badHolders.put(blank, "");
            configErrors = true;
        }

        for (Book leftover : stored) {
            if (configErrors) {
                dormant.put(leftover.id(), leftover);
            } else {
                plugin.getLogger().info("Removed " + leftover.displayName() + " (" + leftover.holder()
                        + ") because it is no longer in effectbooks.yml; its effects will be cleared.");
            }
        }
        if (!dormant.isEmpty()) {
            plugin.getLogger().warning(dormant.size() + " book(s) are inactive until effectbooks.yml errors are fixed.");
        }
        everLoaded = true;
        rememberRaw();
        saveData();
    }

    /**
     * What Bukkit hides: "books:" left empty, holders left blank ("Steve:"), or holder lines that
     * slipped out of the books section (not indented). Read straight from the YAML.
     */
    private record RawCheck(boolean ok, Set<String> blankHolders) {}

    private static final Set<String> SETTINGS = Set.of("books", "check-interval-ticks", "show-particles", "show-icon",
            "absorption-refill-seconds");

    private RawCheck rawCheck() {
        File file = new File(plugin.getDataFolder(), "effectbooks.yml");
        if (!file.exists()) return new RawCheck(true, Set.of());
        Object root;
        try {
            root = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(file.toPath()));
        } catch (IOException | RuntimeException e) {
            return new RawCheck(true, Set.of()); // Bukkit already reported real syntax errors
        }
        if (!(root instanceof Map<?, ?> top)) return new RawCheck(true, Set.of());
        for (Object key : top.keySet()) {
            String k = String.valueOf(key);
            if (!SETTINGS.contains(k) && Effects.isValidName(k)) {
                plugin.getLogger().severe("effectbooks.yml: '" + k + "' is outside 'books:' (it needs to be indented under it).");
                return new RawCheck(false, Set.of());
            }
        }
        Object books = top.get("books");
        if (top.containsKey("books") && books == null && hasStoredBooks()) {
            plugin.getLogger().severe("effectbooks.yml: 'books:' is empty but books exist. Write 'books: {}' to really delete them all.");
            return new RawCheck(false, Set.of());
        }
        Set<String> blank = new HashSet<>();
        if (books instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) if (e.getValue() == null) blank.add(String.valueOf(e.getKey()));
        }
        return new RawCheck(true, blank);
    }

    private boolean hasStoredBooks() {
        try {
            YamlConfiguration data = new YamlConfiguration();
            if (dataFile.exists()) data.load(dataFile);
            ConfigurationSection s = data.getConfigurationSection("books");
            return s != null && !s.getKeys(false).isEmpty();
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            return true;
        }
    }

    /** True while effectbooks.yml or its data file has a YAML error. */
    public boolean isBroken() {
        return broken;
    }

    private void requireWritable() {
        if (!broken && !plugin.configFileParses()) {
            broken = true;
            plugin.getLogger().severe("effectbooks.yml was edited and now has an error. Fix it and run /eb reload.");
        }
        if (broken) {
            throw new IllegalArgumentException("effectbooks.yml (or its data file) has an error. Fix it and run /eb reload first.");
        }
    }

    /** Keep running on these books while the files are broken (used across /sc reload). */
    public void markBroken() {
        broken = true;
    }

    public boolean hasLoaded() {
        return everLoaded;
    }

    public void saveData() {
        if (broken) return;
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
        for (Map.Entry<String, Map<String, Object>> e : badData.entrySet()) data.createSection("books." + e.getKey(), e.getValue());
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save effectbooks-data.yml", e);
        }
    }

    /** Writes the current books back to effectbooks.yml, keeping the file's comments. */
    public void saveConfig() {
        if (broken) return;
        // Start from the file as it is now, so hand edits made since the last reload aren't lost.
        if (!plugin.reloadConfig()) {
            plugin.getLogger().severe("effectbooks.yml was edited and now has an error, so the change was not "
                    + "written to it. Fix the file and run /eb reload.");
            broken = true;
            return;
        }
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("books");
        Map<String, Object> handEdited = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Object now = section.get(key);
                if (now instanceof ConfigurationSection) continue;
                if (!java.util.Objects.equals(now, loadedRaw.get(key))) handEdited.put(key, now);
            }
            for (String key : loadedRaw.keySet()) if (!section.contains(key)) handEdited.put(key, null);
        }
        if (section == null) {
            section = config.createSection("books");
        } else {
            for (String key : section.getKeys(false)) section.set(key, null);
        }
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Book b : sortedBooks()) {
            grouped.computeIfAbsent(b.holder(), k -> new ArrayList<>()).add(b.spec());
        }
        // Put back lines that didn't parse, so a typo is never silently deleted (and its names kept).
        for (Map.Entry<String, List<String>> e : badSpecs.entrySet()) {
            grouped.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            section.set(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0) : e.getValue());
        }
        for (Map.Entry<String, Object> e : badHolders.entrySet()) section.set(e.getKey(), e.getValue());
        // Remember what *we* wrote (before hand edits go on top), so hand edits keep being spotted
        // on later saves too, until /eb reload applies them.
        rememberRaw();
        // Lines changed by hand since the last reload win; /eb reload applies them.
        if (!handEdited.isEmpty()) {
            for (Map.Entry<String, Object> e : handEdited.entrySet()) section.set(e.getKey(), e.getValue());
            plugin.getLogger().warning("effectbooks.yml was edited by hand for " + handEdited.keySet()
                    + "; kept those lines. Run /eb reload to apply them.");
        }
        plugin.saveConfig();
    }

    /** What the books section looked like when we last read or wrote it (to spot hand edits). */
    private final Map<String, Object> loadedRaw = new LinkedHashMap<>();

    private void rememberRaw() {
        loadedRaw.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("books");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Object v = section.get(key);
            if (!(v instanceof ConfigurationSection)) loadedRaw.put(key, v instanceof List<?> l ? new ArrayList<>(l) : v);
        }
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
        requireWritable();
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
        requireWritable();
        books.remove(book.id());
        persist();
    }

    /** Moves the book (and everyone written in it) to a new holder. */
    public void reassign(Book book, String newHolder) {
        requireWritable();
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
        requireWritable();
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
        requireWritable();
        book.setLevel(clampLevel(level));
        persist();
    }

    public void setTargets(Book book, List<String> names) {
        requireWritable();
        book.targets().clear();
        book.targets().addAll(names.subList(0, Math.min(names.size(), MAX_TARGETS)));
        saveData();
        queueRefresh();
    }

    public boolean addTarget(Book book, String name) {
        requireWritable();
        if (!Effects.isValidName(name)) throw new IllegalArgumentException("'" + name + "' is not a valid username.");
        if (book.hasTarget(name)) return false;
        if (book.targets().size() >= MAX_TARGETS) {
            throw new IllegalArgumentException("A book holds at most " + MAX_TARGETS + " names.");
        }
        book.targets().add(name);
        saveData();
        queueRefresh();
        return true;
    }

    public void removeTarget(Book book, String name) {
        requireWritable();
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
        return createItem(book, book.targets());
    }

    /** The book item showing {@code names} (used to rewrite pages before they're saved). */
    public ItemStack createItem(Book book, List<String> names) {
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
        meta.setPages(pagesFor(names));
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

    private List<String> pagesOf(ItemStack item) {
        if (item != null && item.getItemMeta() instanceof BookMeta meta) return parsePages(meta).names();
        return List.of();
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

    /** The real book, the crafting-slot preview of it, or a leftover from the standalone plugin. */
    public boolean isEffectBook(ItemStack item) {
        // Cheap check first: this runs for every slot of every player on each refresh.
        if (item == null || item.getType() != Material.WRITABLE_BOOK || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(bookKey, PersistentDataType.STRING) || pdc.has(displayKey, PersistentDataType.BYTE)
                || pdc.has(LEGACY_BOOK_KEY, PersistentDataType.STRING);
    }

    /** True for the preview item shown in the crafting output slot. */
    public boolean isDisplay(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE);
    }

    /** Books handed out by the old standalone EffectBooks plugin; they are replaced by new ones. */
    private boolean isLegacy(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(LEGACY_BOOK_KEY, PersistentDataType.STRING);
    }

    /** The books this player holds, in a stable order. */
    public List<Book> booksHeldBy(Player player) {
        List<Book> held = new ArrayList<>();
        for (Book b : sortedBooks()) {
            if (b.isHolder(player.getName())) held.add(b);
        }
        return held;
    }

    /**
     * What the crafting output slot shows to a holder while their crafting grid is empty: their book,
     * or a "your books" item if they hold more than one.
     */
    public ItemStack displayItem(List<Book> held) {
        ItemStack item;
        if (held.size() == 1) {
            item = createItem(held.get(0));
            BookMeta meta = (BookMeta) item.getItemMeta();
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to write in it");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        } else {
            item = new ItemStack(Material.WRITABLE_BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.setItemName(ChatColor.LIGHT_PURPLE + "Your Effect Books (" + held.size() + ")");
            meta.setEnchantmentGlintOverride(true);
            List<String> lore = new ArrayList<>();
            for (Book b : held) lore.add(ChatColor.GRAY + "- " + b.displayName());
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to pick one to write in");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------ syncing players

    /** Resync everyone on the next tick (coalesces many changes into one pass). */
    public void queueRefresh() {
        if (refreshQueued) return;
        refreshQueued = true;
        Bukkit.getScheduler().runTask(plugin.core(), () -> {
            refreshQueued = false;
            refreshAll();
        });
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    public void refresh(Player player) {
        if (!player.isOnline()) return;
        if (broken && !everLoaded) return; // nothing real loaded yet: don't strip anyone's effects
        syncBooks(player);
        reconcileEffects(player);
    }

    /**
     * Books don't take up an inventory slot. A holder's book sits in the output slot of their
     * inventory crafting grid (only while that grid is empty) and only comes into their hand while
     * they write in it. Any copy anywhere else is removed; it can always be regenerated from data.
     */
    public void syncBooks(Player player) {
        Map<UUID, Book> owed = new LinkedHashMap<>();
        for (Book b : booksHeldBy(player)) owed.put(b.id(), b);
        Set<UUID> found = new HashSet<>();

        // A book on the cursor (mid-click in an inventory) is left alone if it is theirs.
        ItemStack cursor = player.getItemOnCursor();
        if (isEffectBook(cursor)) {
            UUID cursorId = bookId(cursor);
            if (cursorId != null && owed.containsKey(cursorId) && !isDisplay(cursor)) {
                found.add(cursorId);
            } else {
                player.setItemOnCursor(null);
            }
        }

        PlayerInventory inv = player.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (!isEffectBook(contents[slot])) continue;
            UUID id = bookId(contents[slot]);
            Book book = id == null ? null : owed.get(id);
            // Only the copy in their hand (while writing) may exist.
            if (book == null || slot != held || isDisplay(contents[slot]) || !found.add(id)) {
                inv.setItem(slot, null);
                continue;
            }
            // While it's out, the pages stay as the holder saw them (their edit is merged with any
            // changes made meanwhile when they press Done). Only a changed effect/level/holder is refreshed.
            ItemStack expected = createItem(book, pagesOf(contents[slot]));
            if (!expected.isSimilar(contents[slot])) inv.setItem(slot, expected);
        }

        restoreMovedTool(player, !found.isEmpty());
        updateDisplay(player, new ArrayList<>(owed.values()));
    }

    /** Puts the book preview into the crafting output slot, or clears a stale one. */
    public void updateDisplay(Player player, List<Book> held) {
        InventoryView view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        if (top == null || top.getType() != InventoryType.CRAFTING || !(top instanceof CraftingInventory crafting)) return;

        boolean gridEmpty = true;
        ItemStack[] matrix = crafting.getMatrix();
        for (int i = 0; i < matrix.length; i++) {
            if (isEffectBook(matrix[i])) {
                matrix[i] = null; // books never belong in the grid itself
                crafting.setItem(i + 1, null);
            }
            if (matrix[i] != null && !matrix[i].getType().isAir()) gridEmpty = false;
        }

        ItemStack result = crafting.getResult();
        if (!gridEmpty) {
            // The player is crafting; vanilla owns the output slot. Only clear a leftover preview.
            if (isDisplay(result)) crafting.setResult(null);
            return;
        }
        if (held.isEmpty()) {
            if (isEffectBook(result)) crafting.setResult(null);
            return;
        }
        ItemStack want = displayItem(held);
        if (!want.isSimilar(result)) crafting.setResult(want);
    }

    /** Removes every preview from open crafting grids (used on shutdown). */
    public void clearDisplays() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            // With a chest (or any other screen) open, the inventory's crafting output can't be
            // reached; close it first so the preview can't be taken once the books are off.
            InventoryView open = p.getOpenInventory();
            if (open != null && open.getTopInventory() != null
                    && open.getTopInventory().getType() != InventoryType.CRAFTING && !booksHeldBy(p).isEmpty()) {
                p.closeInventory();
            }
            if (isEffectBook(p.getItemOnCursor())) p.setItemOnCursor(null);
            InventoryView view = p.getOpenInventory();
            if (view != null && view.getTopInventory() instanceof CraftingInventory crafting && isDisplay(crafting.getResult())) crafting.setResult(null);
            PlayerInventory inv = p.getInventory();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                if (isEffectBook(inv.getItem(slot))) inv.setItem(slot, null);
            }
            restoreMovedTool(p, false);
        }
    }

    /** On quit: put the book away (so the tool goes back) and forget per-player state. */
    public void forget(Player player) {
        if (isEffectBook(player.getItemOnCursor())) player.setItemOnCursor(null);
        putAway(player);
        movedTools.remove(player.getUniqueId());
        openedWith.remove(player.getUniqueId());
    }

    /**
     * Puts a book in the holder's hand so they can write in it, without pushing any of their items
     * out: it uses an empty hotbar slot if there is one, otherwise moves the held item into the
     * inventory. Returns false if there's no room at all.
     */
    public boolean giveToHand(Player player, Book book) {
        openedWith.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(book.id(), new ArrayList<>(book.targets()));
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (book.id().equals(bookId(inv.getItem(slot)))) inv.setItem(slot, null);
        }
        // Swapping from one of their books to another: just replace it, don't move it around.
        if (isEffectBook(inv.getItemInMainHand())) inv.setItemInMainHand(null);
        if (isEmpty(inv.getItemInMainHand())) {
            inv.setItemInMainHand(createItem(book));
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isEmpty(inv.getItem(slot))) {
                inv.setHeldItemSlot(slot);
                inv.setItem(slot, createItem(book));
                return true;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (isEmpty(inv.getItem(slot))) {
                ItemStack held = inv.getItemInMainHand();
                inv.setItem(slot, held);
                inv.setItemInMainHand(createItem(book));
                movedTools.putIfAbsent(player.getUniqueId(), new MovedTool(inv.getHeldItemSlot(), slot, held.clone()));
                return true;
            }
        }
        return false;
    }

    /** The names each book had when the holder took it out (to merge with changes made meanwhile). */
    private final Map<UUID, Map<UUID, List<String>>> openedWith = new HashMap<>();

    /**
     * Applies the holder's edit on top of the book's current names: names they erased are removed
     * and names they wrote are added, but names an admin added while they were writing are kept.
     */
    public List<String> mergeEdit(Player player, Book book, List<String> written) {
        Map<UUID, List<String>> mine = openedWith.get(player.getUniqueId());
        List<String> before = mine == null ? null : mine.get(book.id()); // dropped once the edit is saved
        if (before == null) return new ArrayList<>(written);
        Set<String> beforeLower = new HashSet<>();
        for (String n : before) beforeLower.add(n.toLowerCase(Locale.ROOT));
        Set<String> writtenLower = new HashSet<>();
        for (String n : written) writtenLower.add(n.toLowerCase(Locale.ROOT));
        List<String> result = new ArrayList<>(written);
        for (String current : book.targets()) {
            String lower = current.toLowerCase(Locale.ROOT);
            // Added by someone else after they opened it, and not already in what they wrote.
            if (!beforeLower.contains(lower) && !writtenLower.contains(lower)) result.add(current);
        }
        return result;
    }

    /** The edit went through: forget what the book looked like when it was taken out. */
    public void finishEdit(Player player, Book book) {
        Map<UUID, List<String>> mine = openedWith.get(player.getUniqueId());
        if (mine != null) mine.remove(book.id());
    }

    /** Where giveToHand moved a player's held item to, so it can go back afterwards. */
    private record MovedTool(int handSlot, int storageSlot, ItemStack item) {}
    private final Map<UUID, MovedTool> movedTools = new HashMap<>();

    /** Takes the book back out of the player's hand (it returns to its crafting-slot spot). */
    public void putAway(Player player) {
        PlayerInventory inv = player.getInventory();
        if (bookId(inv.getItemInMainHand()) != null) inv.setItemInMainHand(null);
        syncBooks(player);
    }

    /** Once the book is put away, the item that made room for it goes back to the player's hand slot. */
    private void restoreMovedTool(Player player, boolean bookStillOut) {
        MovedTool moved = movedTools.get(player.getUniqueId());
        if (moved == null || bookStillOut) return;
        movedTools.remove(player.getUniqueId());
        PlayerInventory inv = player.getInventory();
        if (isEmpty(inv.getItem(moved.handSlot())) && moved.item().isSimilar(inv.getItem(moved.storageSlot()))) {
            inv.setItem(moved.handSlot(), inv.getItem(moved.storageSlot()));
            inv.setItem(moved.storageSlot(), null);
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
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

    /** When each player's book Absorption last ran out (its hearts were used up). */
    private final Map<UUID, Long> absorptionUsedUp = new HashMap<>();

    /** Called when book Absorption wears off because its golden hearts are gone. */
    public void absorptionUsedUp(Player player) {
        absorptionUsedUp.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Applies the effects this player should have and removes ones they no longer should. */
    public void reconcileEffects(Player player) {
        Map<PotionEffectType, Integer> desired = desiredEffects(player.getName());
        desired.keySet().removeIf(PotionEffectType::isInstant);
        Map<PotionEffectType, Integer> managed = readManaged(player);

        for (Map.Entry<PotionEffectType, Integer> e : managed.entrySet()) {
            Integer want = desired.get(e.getKey());
            if (want != null && want.equals(e.getValue())) continue;
            removeOurs(player, e.getKey(), e.getValue());
        }

        boolean particles = plugin.getConfig().getBoolean("show-particles", false);
        boolean icon = plugin.getConfig().getBoolean("show-icon", true);
        long refill = Math.max(0, plugin.getConfig().getLong("absorption-refill-seconds", 30)) * 1000L;
        for (Map.Entry<PotionEffectType, Integer> e : desired.entrySet()) {
            PotionEffectType type = e.getKey();
            PotionEffect current = player.getPotionEffect(type);
            int amp = e.getValue();
            boolean ours = current != null && hasOurs(current, amp);
            if (type.equals(PotionEffectType.ABSORPTION) && !ours) {
                // Its golden hearts were used up; give them back after a while, not every check
                // (and not straight away just because a weaker golden apple is active).
                Long usedUp = absorptionUsedUp.get(player.getUniqueId());
                if (usedUp != null && System.currentTimeMillis() - usedUp < refill) continue;
                absorptionUsedUp.remove(player.getUniqueId());
            }
            boolean onTop = current != null && current.isInfinite() && current.getAmplifier() == amp;
            if (onTop && (current.hasParticles() != particles || current.hasIcon() != icon)) {
                // Settings changed, or a beacon/potion copied its own flags onto ours: vanilla
                // updates the flags in place when the same effect is added again (no removal, so
                // Health Boost hearts and Absorption aren't touched).
                player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, true, particles, icon));
                continue;
            } else if (ours) {
                continue;
            }
            if (current == null || current.getAmplifier() < amp
                    || (current.getAmplifier() == amp && !current.isInfinite())) {
                player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, true, particles, icon));
            }
        }

        if (!desired.equals(managed)) writeManaged(player, desired);
    }

    /** Our infinite effect is this one, or hidden underneath it. */
    public static boolean hasOurs(PotionEffect effect, int amp) {
        for (PotionEffect e = effect; e != null; e = e.getHiddenPotionEffect()) {
            if (e.isInfinite() && e.getAmplifier() == amp) return true;
        }
        return false;
    }

    /**
     * Removes the infinite effect we gave, even when a stronger potion is currently covering it
     * (vanilla keeps ours "hidden" underneath and would bring it back when the potion ends).
     * Anything else of that type stays exactly as it was.
     */
    private void removeOurs(Player player, PotionEffectType type, int amp) {
        PotionEffect current = player.getPotionEffect(type);
        if (current == null) return;
        List<PotionEffect> chain = new ArrayList<>();
        boolean found = false;
        for (PotionEffect e = current; e != null; e = e.getHiddenPotionEffect()) {
            if (!found && e.isInfinite() && e.getAmplifier() == amp) {
                found = true;
                continue;
            }
            chain.add(e);
        }
        if (!found) return;
        player.removePotionEffect(type);
        // Re-add the rest from the bottom up, so vanilla rebuilds the same stack.
        for (int i = chain.size() - 1; i >= 0; i--) {
            PotionEffect e = chain.get(i);
            player.addPotionEffect(new PotionEffect(type, e.getDuration(), e.getAmplifier(), e.isAmbient(),
                    e.hasParticles(), e.hasIcon()));
        }
    }

    /** Takes book effects off everyone online (when books are turned off). The record is kept for later. */
    public void stripEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) stripEffects(p);
    }

    public void stripEffects(Player player) {
        for (Map.Entry<PotionEffectType, Integer> e : readManaged(player).entrySet()) {
            removeOurs(player, e.getKey(), e.getValue());
        }
    }

    private Map<PotionEffectType, Integer> readManaged(Player player) {
        Map<PotionEffectType, Integer> map = new HashMap<>();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String legacy = pdc.get(LEGACY_MANAGED_KEY, PersistentDataType.STRING);
        if (legacy != null) {
            // Carry over what the standalone plugin gave this player so it can still be cleaned up.
            if (!pdc.has(managedKey, PersistentDataType.STRING)) pdc.set(managedKey, PersistentDataType.STRING, legacy);
            pdc.remove(LEGACY_MANAGED_KEY);
        }
        String raw = pdc.get(managedKey, PersistentDataType.STRING);
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

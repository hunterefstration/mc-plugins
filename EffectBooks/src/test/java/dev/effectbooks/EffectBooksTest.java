package dev.effectbooks;

import dev.effectbooks.model.Book;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EffectBooksTest {

    private ServerMock server;
    private EffectBooksPlugin plugin;
    private BookManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(EffectBooksPlugin.class);
        plugin.getConfig().set("books.Steve", "SPEED");
        plugin.getConfig().set("books.Alex", List.of("STRENGTH:2", "night_vision"));
        plugin.saveConfig();
        plugin.reload();
        manager = plugin.manager();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack findBook(PlayerMock player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (manager.isEffectBook(item)) return item;
        }
        return null;
    }

    private int countBooks(PlayerMock player) {
        int n = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (manager.isEffectBook(item)) n++;
        }
        return n;
    }

    /** Simulates the holder typing names into the book and pressing Done. */
    private PlayerEditBookEvent write(PlayerMock holder, String... pages) {
        ItemStack book = findBook(holder);
        assertNotNull(book, "holder should have a book");
        BookMeta prev = (BookMeta) book.getItemMeta();
        BookMeta next = prev.clone();
        next.setPages(pages);
        PlayerEditBookEvent event = new PlayerEditBookEvent(holder, 0, prev, next, false);
        server.getPluginManager().callEvent(event);
        server.getScheduler().performTicks(2);
        return event;
    }

    @Test
    void configIsParsed() {
        assertEquals(3, manager.books().size());
        Book strength = manager.find("alex", PotionEffectType.STRENGTH);
        assertNotNull(strength);
        assertEquals(2, strength.level());
        assertEquals("Book of Strength II", strength.displayName());
        assertNotNull(manager.find("Alex", PotionEffectType.NIGHT_VISION));
    }

    @Test
    void holderGetsGlowingBookOnJoin() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);
        assertNotNull(book);
        assertEquals(Material.WRITABLE_BOOK, book.getType());
        BookMeta meta = (BookMeta) book.getItemMeta();
        assertTrue(meta.getEnchantmentGlintOverride());
        assertTrue(meta.getItemName().contains("Book of Speed"));

        PlayerMock alex = server.addPlayer("Alex");
        server.getScheduler().performTicks(2);
        assertEquals(2, countBooks(alex));

        PlayerMock nobody = server.addPlayer("Nobody");
        server.getScheduler().performTicks(2);
        assertEquals(0, countBooks(nobody));
    }

    @Test
    void writingNamesGrantsPermanentEffect() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carl = server.addPlayer("Carl");
        server.getScheduler().performTicks(2);

        write(steve, "Bob\ncarl\n  \nnot a name!\nBob");

        Book book = manager.find("Steve", PotionEffectType.SPEED);
        assertEquals(List.of("Bob", "carl"), book.targets());

        PotionEffect speed = bob.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed);
        assertTrue(speed.isInfinite());
        assertEquals(0, speed.getAmplifier());
        assertNotNull(carl.getPotionEffect(PotionEffectType.SPEED));
        assertNull(steve.getPotionEffect(PotionEffectType.SPEED));

        // Book pages were rewritten cleanly.
        BookMeta meta = (BookMeta) findBook(steve).getItemMeta();
        assertEquals("Bob\ncarl", meta.getPage(1));
    }

    @Test
    void erasingNameRemovesEffect() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");
        assertNotNull(bob.getPotionEffect(PotionEffectType.SPEED));

        write(steve, "");
        assertNull(bob.getPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void erasingNameDoesNotTouchNormalPotions() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");
        bob.removePotionEffect(PotionEffectType.SPEED);
        bob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 600, 0));
        write(steve, "");
        assertNotNull(bob.getPotionEffect(PotionEffectType.SPEED), "a drunk potion should survive");
    }

    @Test
    void milkDeathTotemAndCommandsCannotRemoveIt() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");
        PotionEffect speed = bob.getPotionEffect(PotionEffectType.SPEED);

        for (EntityPotionEffectEvent.Cause cause : List.of(EntityPotionEffectEvent.Cause.MILK,
                EntityPotionEffectEvent.Cause.DEATH, EntityPotionEffectEvent.Cause.TOTEM,
                EntityPotionEffectEvent.Cause.COMMAND)) {
            EntityPotionEffectEvent event = new EntityPotionEffectEvent(bob, speed, null, cause,
                    EntityPotionEffectEvent.Action.CLEARED, false);
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), cause + " should be blocked");
        }

        // Even if something bypasses the event, the periodic check puts it back.
        bob.removePotionEffect(PotionEffectType.SPEED);
        assertNull(bob.getPotionEffect(PotionEffectType.SPEED));
        server.getScheduler().performTicks(60);
        assertNotNull(bob.getPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void offlineTargetGetsEffectOnJoin() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        write(steve, "Latecomer");
        PlayerMock late = server.addPlayer("Latecomer");
        server.getScheduler().performTicks(2);
        assertNotNull(late.getPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void highestLevelWinsAcrossBooks() {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        Book weak = manager.create("Steve", PotionEffectType.STRENGTH, 1);
        manager.addTarget(weak, "Bob");
        manager.addTarget(manager.find("Alex", PotionEffectType.STRENGTH), "Bob");
        server.getScheduler().performTicks(2);
        assertEquals(1, bob.getPotionEffect(PotionEffectType.STRENGTH).getAmplifier());
    }

    @Test
    void onlyHolderCanWrite() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock thief = server.addPlayer("Thief");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);
        BookMeta prev = (BookMeta) book.getItemMeta();
        BookMeta next = prev.clone();
        next.setPages("Thief");
        PlayerEditBookEvent event = new PlayerEditBookEvent(thief, 0, prev, next, false);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        assertTrue(manager.find("Steve", PotionEffectType.SPEED).targets().isEmpty());
    }

    @Test
    void signingIsPrevented() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);
        BookMeta prev = (BookMeta) book.getItemMeta();
        BookMeta next = prev.clone();
        next.setPages("Bob");
        PlayerEditBookEvent event = new PlayerEditBookEvent(steve, 0, prev, next, true);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isSigning());
        assertEquals(List.of("Bob"), manager.find("Steve", PotionEffectType.SPEED).targets());
    }

    @Test
    void bookCannotBeDroppedAndIsKeptThroughDeath() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);

        Item dropped = steve.getWorld().dropItem(steve.getLocation(), book);
        PlayerDropItemEvent drop = new PlayerDropItemEvent(steve, dropped);
        server.getPluginManager().callEvent(drop);
        assertTrue(drop.isCancelled());

        List<ItemStack> drops = new ArrayList<>(List.of(book, new ItemStack(Material.DIRT)));
        PlayerDeathEvent death = new PlayerDeathEvent(steve,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.LAVA).build(), drops, 0, "died");
        server.getPluginManager().callEvent(death);
        assertEquals(1, death.getDrops().size());
        assertEquals(Material.DIRT, death.getDrops().get(0).getType());
    }

    @Test
    void lostOrDestroyedBookComesBack() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        steve.getInventory().clear(); // lava, /clear, void death...
        assertNull(findBook(steve));
        server.getScheduler().performTicks(60);
        assertNotNull(findBook(steve));
    }

    @Test
    void duplicatesAndStrangersCopiesAreRemoved() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock other = server.addPlayer("Other");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);
        steve.getInventory().addItem(book.clone());
        other.getInventory().addItem(book.clone());
        server.getScheduler().performTicks(60);
        assertEquals(1, countBooks(steve));
        assertEquals(0, countBooks(other));
    }

    @Test
    void reassignMovesBookAndKeepsNames() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock notch = server.addPlayer("Notch");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");

        Book book = manager.find("Steve", PotionEffectType.SPEED);
        manager.reassign(book, "Notch");
        server.getScheduler().performTicks(2);

        assertEquals(0, countBooks(steve));
        assertEquals(1, countBooks(notch));
        assertNotNull(bob.getPotionEffect(PotionEffectType.SPEED), "names carry over");
        assertEquals("SPEED", plugin.getConfig().getString("books.Notch"));
        assertNull(plugin.getConfig().get("books.Steve"));

        // New holder can now edit it.
        write(notch, "");
        assertNull(bob.getPotionEffect(PotionEffectType.SPEED));
    }

    @Test
    void deletingBookClearsEffectsEvenForOfflinePlayers() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");
        assertNotNull(bob.getPotionEffect(PotionEffectType.SPEED));

        manager.delete(manager.find("Steve", PotionEffectType.SPEED));
        server.getScheduler().performTicks(2);
        assertNull(bob.getPotionEffect(PotionEffectType.SPEED));
        assertEquals(0, countBooks(steve));
        assertNull(plugin.getConfig().get("books.Steve"));
    }

    @Test
    void levelChangeReplacesEffect() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");
        Book book = manager.find("Steve", PotionEffectType.SPEED);
        manager.setLevel(book, 3);
        server.getScheduler().performTicks(2);
        assertEquals(2, bob.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        manager.setLevel(book, 1);
        server.getScheduler().performTicks(2);
        assertEquals(0, bob.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertEquals("SPEED", plugin.getConfig().getString("books.Steve"));
    }

    @Test
    void namesSurviveReload() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        write(steve, "Bob\nCarl");
        plugin.reload();
        assertEquals(List.of("Bob", "Carl"), plugin.manager().find("Steve", PotionEffectType.SPEED).targets());
    }

    @Test
    void typoInConfigDoesNotWipeNames() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");

        plugin.getConfig().set("books.Steve", "SPEEED");
        plugin.saveConfig();
        plugin.reload();
        assertNull(manager.find("Steve", PotionEffectType.SPEED));

        plugin.getConfig().set("books.Steve", "SPEED");
        plugin.saveConfig();
        plugin.reload();
        assertEquals(List.of("Bob"), manager.find("Steve", PotionEffectType.SPEED).targets());
    }

    @Test
    void removingFromConfigDeletesBook() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        write(steve, "Bob");

        plugin.getConfig().set("books.Steve", null);
        plugin.saveConfig();
        plugin.reload();
        server.getScheduler().performTicks(2);
        assertNull(bob.getPotionEffect(PotionEffectType.SPEED));
        assertEquals(0, countBooks(steve));
    }

    @Test
    void commandsWork() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        assertTrue(admin.performCommand("eb add Herobrine haste 2"));
        assertNotNull(manager.find("Herobrine", PotionEffectType.HASTE));
        assertEquals("HASTE:2", plugin.getConfig().getString("books.Herobrine"));
        assertTrue(admin.performCommand("eb delete Herobrine haste"));
        assertNull(manager.find("Herobrine", PotionEffectType.HASTE));
        assertTrue(admin.performCommand("eb"));
        assertInstanceOf(dev.effectbooks.gui.MainMenu.class,
                admin.getOpenInventory().getTopInventory().getHolder());
    }

    private Object topHolder(PlayerMock player) {
        return player.getOpenInventory().getTopInventory().getHolder();
    }

    private void click(PlayerMock player, int slot) {
        player.simulateInventoryClick(player.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, slot);
        server.getScheduler().performTicks(2);
    }

    @SuppressWarnings("deprecation")
    private void chat(PlayerMock player, String message) {
        server.getPluginManager().callEvent(new org.bukkit.event.player.AsyncPlayerChatEvent(
                false, player, message, new java.util.HashSet<>()));
        server.getScheduler().performTicks(2);
    }

    @Test
    void menuDeleteFlow() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        PlayerMock bob = server.addPlayer("Bob");
        server.getScheduler().performTicks(2);
        manager.addTarget(manager.find("Alex", PotionEffectType.NIGHT_VISION), "Bob");
        server.getScheduler().performTicks(2);
        assertNotNull(bob.getPotionEffect(PotionEffectType.NIGHT_VISION));

        admin.performCommand("eb");
        // sorted: Alex/Night Vision, Alex/Strength, Steve/Speed
        click(admin, 0);
        assertInstanceOf(dev.effectbooks.gui.BookMenu.class, topHolder(admin));
        click(admin, 16);
        assertInstanceOf(dev.effectbooks.gui.ConfirmMenu.class, topHolder(admin));
        click(admin, 11);
        assertInstanceOf(dev.effectbooks.gui.MainMenu.class, topHolder(admin));
        assertNull(manager.find("Alex", PotionEffectType.NIGHT_VISION));
        assertNull(bob.getPotionEffect(PotionEffectType.NIGHT_VISION));
    }

    @Test
    void menuReassignAndTargetsFlow() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock notch = server.addPlayer("Notch");
        server.getScheduler().performTicks(2);

        admin.performCommand("eb");
        click(admin, 2); // Steve / Speed
        assertInstanceOf(dev.effectbooks.gui.BookMenu.class, topHolder(admin));

        click(admin, 10); // reassign -> chat prompt
        chat(admin, "Notch");
        assertNotNull(manager.find("Notch", PotionEffectType.SPEED));
        assertEquals(0, countBooks(steve));
        assertEquals(1, countBooks(notch));
        assertInstanceOf(dev.effectbooks.gui.BookMenu.class, topHolder(admin));

        click(admin, 14); // targets
        assertInstanceOf(dev.effectbooks.gui.TargetsMenu.class, topHolder(admin));
        click(admin, 48); // add player
        chat(admin, "Bob");
        Book book = manager.find("Notch", PotionEffectType.SPEED);
        assertEquals(List.of("Bob"), book.targets());
        // the holder's physical book shows the new name
        assertEquals("Bob", ((BookMeta) findBook(notch).getItemMeta()).getPage(1));

        click(admin, 0); // click Bob's head -> removed
        assertTrue(book.targets().isEmpty());

        click(admin, 49); // back
        click(admin, 12); // level +1
        assertEquals(2, book.level());
        assertEquals("SPEED:2", plugin.getConfig().getString("books.Notch"));
    }

    @Test
    void menuAddBookFlow() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        admin.performCommand("eb");
        click(admin, 49);
        chat(admin, "Newbie");
        assertInstanceOf(dev.effectbooks.gui.EffectPickerMenu.class, topHolder(admin));
        java.util.List<PotionEffectType> effects = dev.effectbooks.util.Effects.selectable();
        click(admin, effects.indexOf(PotionEffectType.JUMP_BOOST));
        assertNotNull(manager.find("Newbie", PotionEffectType.JUMP_BOOST));
        assertEquals("JUMP_BOOST", plugin.getConfig().getString("books.Newbie"));
        assertInstanceOf(dev.effectbooks.gui.BookMenu.class, topHolder(admin));
    }

    @Test
    void cannotPutBookInChest() {
        PlayerMock steve = server.addPlayer("Steve");
        server.getScheduler().performTicks(2);
        ItemStack book = findBook(steve);
        org.bukkit.inventory.Inventory chest = server.createInventory(null, 27);
        org.bukkit.inventory.InventoryView view = steve.openInventory(chest);

        steve.setItemOnCursor(book);
        org.bukkit.event.inventory.InventoryClickEvent place = new org.bukkit.event.inventory.InventoryClickEvent(
                view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PLACE_ALL);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled(), "placing into chest");
        steve.setItemOnCursor(null);

        int slot = -1;
        for (int raw = 27; raw < 63; raw++) {
            if (manager.isEffectBook(view.getItem(raw))) slot = raw;
        }
        assertTrue(slot >= 0);
        org.bukkit.event.inventory.InventoryClickEvent shift = new org.bukkit.event.inventory.InventoryClickEvent(
                view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
        server.getPluginManager().callEvent(shift);
        assertTrue(shift.isCancelled(), "shift-clicking into chest");

        org.bukkit.event.inventory.InventoryClickEvent dirt = new org.bukkit.event.inventory.InventoryClickEvent(
                view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PLACE_ALL);
        server.getPluginManager().callEvent(dirt);
        assertFalse(dirt.isCancelled(), "normal clicks unaffected");
    }

    @Test
    void effectPickerShowsEveryEffectWithRealNames() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        admin.performCommand("eb");
        click(admin, 49);
        chat(admin, "Newbie");
        org.bukkit.inventory.Inventory top = admin.getOpenInventory().getTopInventory();

        java.util.List<PotionEffectType> all = new java.util.ArrayList<>();
        org.bukkit.Registry.EFFECT.forEach(all::add);
        assertEquals(all.size(), dev.effectbooks.util.Effects.selectable().size(), "every effect is offered");
        assertTrue(dev.effectbooks.util.Effects.selectable().contains(PotionEffectType.INSTANT_HEALTH));

        java.util.Set<String> names = new java.util.HashSet<>();
        for (int slot = 0; slot < all.size(); slot++) {
            ItemStack icon = top.getItem(slot);
            assertNotNull(icon, "slot " + slot);
            assertNotEquals(Material.POTION, icon.getType(), "themed icon for slot " + slot);
            String name = org.bukkit.ChatColor.stripColor(icon.getItemMeta().getItemName());
            assertFalse(name.contains("Potion"), name);
            names.add(name);
        }
        assertTrue(names.contains("Speed"));
        assertTrue(names.contains("Jump Boost"));
        assertTrue(names.contains("Hero of the Village"));
        assertTrue(names.contains("Dolphin's Grace"));
        assertTrue(names.contains("Bad Luck"));
        assertTrue(names.contains("Instant Health"));
    }

    @Test
    void everyEffectHasIconAndDescription() {
        for (String id : List.of("speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health",
                "instant_damage", "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance",
                "water_breathing", "invisibility", "blindness", "night_vision", "hunger", "weakness", "poison",
                "wither", "health_boost", "absorption", "saturation", "glowing", "levitation", "luck", "unluck",
                "slow_falling", "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness",
                "trial_omen", "raid_omen", "wind_charged", "weaving", "oozing", "infested")) {
            PotionEffectType type = dev.effectbooks.util.Effects.parse(id);
            assertNotNull(type, id);
            assertNotNull(dev.effectbooks.util.Effects.iconMaterial(type), "icon for " + id);
            assertNotNull(dev.effectbooks.util.Effects.description(type), "description for " + id);
        }
    }

    @Test
    void instantEffectsPulse() {
        PlayerMock bob = server.addPlayer("Bob");
        Book book = manager.create("Steve", PotionEffectType.INSTANT_HEALTH, 1);
        assertEquals(List.of("INSTANT_HEALTH", "SPEED"), plugin.getConfig().getStringList("books.Steve"));
        manager.addTarget(book, "Bob");
        server.getScheduler().performTicks(2);
        assertNull(bob.getPotionEffect(PotionEffectType.INSTANT_HEALTH), "not given as an infinite effect");

        int[] doses = {0};
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void on(EntityPotionEffectEvent e) {
                if (e.getEntity() == bob && e.getModifiedType().equals(PotionEffectType.INSTANT_HEALTH)
                        && e.getAction() == EntityPotionEffectEvent.Action.ADDED) doses[0]++;
            }
        }, plugin);
        server.getScheduler().performTicks(81);
        assertTrue(doses[0] >= 2, "pulsed every check, got " + doses[0]);

        manager.removeTarget(book, "Bob");
        server.getScheduler().performTicks(2);
        int before = doses[0];
        server.getScheduler().performTicks(81);
        assertEquals(before, doses[0], "stops once erased");
    }
}

package at.esoren.vystormveins;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Ore map: a panel with a chest fallback for the 9 x 5 chunks around the player, showing ores in a band around the
 * player's height. Only loaded chunks are read; the snapshots are counted off the server thread. Needs Vystorm Core
 * (managed inventories and the /vmenu entry); only created when Core is installed.
 */
final class OreMapMenu {
    private static final int SIZE = 54, INFO = 45, REFRESH = 47, FILTER = 49, CLOSE = 53;
    /** "My mining" (1.24.0): vein mining switch, journal, release the claim. */
    static final int VEIN = 50, JOURNAL = 51, CLAIM = 52;
    private final VeinsPlugin plugin;
    private final API core;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> scanning = new HashSet<>();
    private Screen screen;

    /** The panel screen; the chest menu is the fallback when it cannot be registered or opened. */
    interface Screen {
        boolean open(Player player, View view);
        void forget(UUID player);
        void clear();
    }

    /** One open map: what was scanned, where, and the current ore filter. */
    static final class View {
        final List<OreMap.Tile> tiles; final UUID world; final int centreX, centreZ, fromY, toY;
        String filter;
        /** The chest's release button was clicked once; the second click releases. */
        boolean releaseArmed;
        View(List<OreMap.Tile> tiles, UUID world, int centreX, int centreZ, int fromY, int toY) {
            this.tiles = tiles; this.world = world; this.centreX = centreX; this.centreZ = centreZ; this.fromY = fromY; this.toY = toY;
        }
    }

    OreMapMenu(VeinsPlugin plugin, API core) {
        this.plugin = plugin;
        this.core = core;
        try {
            screen = new OreMapPanel(plugin, this, core.gui().panels());
        } catch (RuntimeException unavailable) {
            plugin.getLogger().fine("Ore map panels unavailable: " + unavailable);
        }
        core.gui().registerMenu(plugin, "ore-map", player -> plugin.word(player, "map-menu-title"),
                Material.FILLED_MAP, player -> plugin.oreMap().enabled() && player.hasPermission("vystormveins.map"), this::open,
                MenuCategory.WORLD.group(), player -> plugin.word(player, "map-menu-description"));
    }

    void forget(UUID player) {
        cooldowns.remove(player); scanning.remove(player);
        if (screen != null) screen.forget(player);
    }
    void clearCooldowns() { cooldowns.clear(); if (screen != null) screen.clear(); }

    /** Clickable chat line under a rod result that opens the map. */
    void hint(Player player) {
        CoreComponents.send(core, player, Component.text("[VystormVeins] ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)
                .append(plugin.texts().component(player, "map-hint")).append(Component.space())
                .append(CoreComponents.button(core, plugin.word(player, "map-button"), plugin.word(player, "map-button-hover"),
                        "vystormveins.map", this::open)));
    }

    /** Server thread: checks permission and cooldown, snapshots the loaded chunks and opens the map when counted. */
    void open(Player player) {
        scan(player, false);
    }

    void openChest(Player player) { scan(player, true); }

    private void scan(Player player, boolean chest) {
        if (!plugin.isEnabled() || !player.isOnline()) return;
        OreMap.Options options = plugin.oreMap();
        if (!options.enabled()) { plugin.say(player, "map-off"); return; }
        if (!player.hasPermission("vystormveins.map")) { plugin.say(player, "denied"); return; }
        if (scanning.contains(player.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long ready = cooldowns.get(player.getUniqueId());
        if (ready != null && now < ready) { plugin.say(player, "map-cooldown", (ready - now + 999) / 1000); return; }
        cooldowns.put(player.getUniqueId(), now + options.cooldownMillis());
        World world = player.getWorld();
        int cx = player.getLocation().getBlockX() >> 4, cz = player.getLocation().getBlockZ() >> 4, y = player.getLocation().getBlockY();
        int fromY = Math.max(world.getMinHeight(), y - options.depth()), toY = Math.min(world.getMaxHeight() - 1, y + options.depth());
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        ChunkSnapshot[] snapshots = new ChunkSnapshot[OreMap.COLUMNS * OreMap.ROWS];
        for (int slot = 0; slot < snapshots.length; slot++) {
            int[] d = OreMap.offset(slot);
            if (world.isChunkLoaded(cx + d[0], cz + d[1])) snapshots[slot] = world.getChunkAt(cx + d[0], cz + d[1]).getChunkSnapshot(false, false, false);
        }
        Set<String> extra = plugin.depositOres(), hidden = plugin.prospectingOptions().hidden();
        scanning.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<OreMap.Tile> tiles = new ArrayList<>();
            for (int slot = 0; slot < snapshots.length; slot++) {
                int[] d = OreMap.offset(slot);
                ChunkSnapshot snapshot = snapshots[slot];
                tiles.add(snapshot == null ? new OreMap.Tile(cx + d[0], cz + d[1], false, Map.of())
                        : new OreMap.Tile(cx + d[0], cz + d[1], true, OreMap.tally((x, by, z) -> snapshot.getBlockType(x, by, z).getKey().toString(),
                                fromY, toY, minY, maxY, extra, hidden)));
            }
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                scanning.remove(player.getUniqueId());
                if (!player.isOnline() || !player.getWorld().equals(world) || !plugin.isEnabled()
                        || plugin.oreMap() != options || !player.hasPermission("vystormveins.map")) return;
                View view = new View(tiles, world.getUID(), cx, cz, fromY, toY);
                if (!chest && screen != null) {
                    try { if (screen.open(player, view)) return; }
                    catch (RuntimeException unavailable) {
                        plugin.getLogger().fine("Ore map panel failed to open: " + unavailable);
                    }
                }
                showChest(player, view);
            });
        });
    }

    void showChest(Player player, View view) {
        if (!allowed(player, view)) return;
        Inventory inventory = core.gui().createInventory(plugin, null, player.getUniqueId(), SIZE,
                plugin.word(player, "map-title"), new GuiCallbacks() {
                    @Override public void click(InventoryClickEvent event) {
                        event.setCancelled(true);
                        int slot = event.getRawSlot();
                        if (slot < 0 || slot >= SIZE) return;
                        ClickType type = event.getClick();
                        Inventory clicked = event.getInventory();
                        plugin.getServer().getScheduler().runTask(plugin, () -> clicked(player, clicked, view, slot, type));
                    }
                });
        render(player, inventory, view);
        player.openInventory(inventory);
    }

    private void clicked(Player player, Inventory inventory, View view, int slot, ClickType type) {
        if (!player.isOnline()) return;
        if (slot == CLOSE) { player.closeInventory(); return; }
        if (!allowed(player, view)) return;
        if (slot == REFRESH) { player.closeInventory(); open(player); return; }
        if (slot == FILTER) {
            view.filter = OreMap.cycle(OreMap.families(view.tiles), view.filter, type.isRightClick() ? -1 : 1);
            render(player, inventory, view);
            return;
        }
        if (slot == VEIN || slot == JOURNAL || slot == CLAIM) {
            MyMining mine = MyMining.of(plugin, player);
            if (slot == VEIN && mine.veinMining() != null) MyMining.setVeinMining(plugin, player, !mine.veinMining());
            if (slot == CLAIM && mine.claims() && mine.claim() != null) {
                if (view.releaseArmed) { view.releaseArmed = false; MyMining.release(plugin, player); }
                else view.releaseArmed = true;
            }
            if (slot == JOURNAL && mine.journal() != null) plugin.journal(player);
            render(player, inventory, view);
            return;
        }
        view.releaseArmed = false;
        describe(player, view, slot);
    }

    boolean allowed(Player player, View view) {
        return plugin.isEnabled() && player.isOnline() && plugin.oreMap().enabled()
                && player.hasPermission("vystormveins.map") && player.getWorld().getUID().equals(view.world);
    }

    void describe(Player player, View view, int slot) {
        if (!allowed(player, view) || slot < 0 || slot >= view.tiles.size()) return;
        OreMap.Tile tile = view.tiles.get(slot);
        if (!tile.loaded()) { plugin.say(player, "map-chunk-unloaded"); return; }
        String ores = tile.ores().isEmpty() ? plugin.word(player, "map-none")
                : String.join(", ", tile.ores().entrySet().stream().map(e -> plugin.oreName(player, e.getKey()) + " " + e.getValue()).toList());
        int dx = tile.chunkX() - view.centreX, dz = tile.chunkZ() - view.centreZ;
        if (dx == 0 && dz == 0) { plugin.say(player, "map-chunk-here", ores); return; }
        String heading = Prospecting.heading(dx, dz);
        plugin.say(player, "map-chunk", tile.chunkX(), tile.chunkZ(), Math.round(Math.sqrt(dx * dx + dz * dz) * 16),
                plugin.word(player, "directions." + heading), ores);
    }

    private void render(Player player, Inventory inventory, View view) {
        for (int slot = 0; slot < view.tiles.size(); slot++) inventory.setItem(slot, tile(player, view, slot));
        inventory.setItem(INFO, item(Material.COMPASS, plugin.texts().component(player, "map-info"),
                List.of(plugin.texts().component(player, "map-info-band", view.fromY, view.toY),
                        plugin.texts().component(player, "map-info-north"),
                        plugin.texts().component(player, "map-info-click"))));
        inventory.setItem(REFRESH, item(Material.SPYGLASS, plugin.texts().component(player, "map-refresh"),
                List.of(plugin.texts().component(player, "map-refresh-hint", plugin.oreMap().cooldownMillis() / 1000))));
        String filter = view.filter == null ? plugin.word(player, "map-filter-all") : plugin.oreName(player, view.filter);
        inventory.setItem(FILTER, item(view.filter == null ? Material.HOPPER : OreMap.icon(view.filter),
                plugin.texts().component(player, "map-filter", filter), List.of(plugin.texts().component(player, "map-filter-hint"))));
        inventory.setItem(CLOSE, item(Material.BARRIER, plugin.texts().component(player, "map-close"), List.of()));
        mine(player, inventory, view, MyMining.of(plugin, player));
    }

    /** The "My mining" buttons of the chest (1.24.0); a part the player may not use stays empty. */
    private void mine(Player player, Inventory inventory, View view, MyMining mine) {
        var t = plugin.texts();
        inventory.setItem(VEIN, null);
        inventory.setItem(JOURNAL, null);
        inventory.setItem(CLAIM, null);
        if (mine.veinMining() != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(t.component(player, "mine-vein-text"));
            if (mine.serverOff()) lore.add(t.component(player, "veinmine-server-off"));
            lore.add(t.component(player, "mine-click-switch"));
            inventory.setItem(VEIN, item(mine.veinMining() ? Material.DIAMOND_PICKAXE : Material.WOODEN_PICKAXE,
                    t.component(player, mine.veinMining() ? "mine-vein-on" : "mine-vein-off"), lore));
        }
        if (mine.journal() != null) {
            MyMining.Journal j = mine.journal();
            List<Component> lore = new ArrayList<>();
            for (MyMining.Line line : j.lines())
                lore.add(t.component(player, "journal-line", line.ore(), line.count(), line.tier(), j.goals(),
                        line.next() == null ? plugin.word(player, "journal-complete") : line.next()));
            lore.add(t.component(player, "journal-goals", j.collectionTier()));
            inventory.setItem(JOURNAL, item(Material.WRITABLE_BOOK, t.component(player, "journal-header", j.total(), j.discovered(), j.families()), lore));
        }
        if (mine.claims()) {
            List<Component> lore = new ArrayList<>();
            if (mine.claim() == null) lore.add(t.component(player, "claim-none"));
            else {
                lore.add(t.component(player, "mine-claim-status", mine.claim().blocks(), mine.claim().ore(), mine.claim().minutes()));
                lore.add(t.component(player, view.releaseArmed ? "mine-release-again" : "mine-release-click"));
            }
            inventory.setItem(CLAIM, item(view.releaseArmed ? Material.REDSTONE : Material.IRON_SHOVEL, t.component(player, "mine-claim"), lore));
        }
    }

    private ItemStack tile(Player player, View view, int slot) {
        OreMap.Tile tile = view.tiles.get(slot);
        boolean here = slot == OreMap.CENTRE;
        if (!tile.loaded()) return item(Material.BLACK_STAINED_GLASS_PANE, plugin.texts().component(player, "map-unloaded"), List.of());
        String shown = tile.shown(view.filter);
        List<Component> lore = new ArrayList<>();
        for (var ore : tile.ores().entrySet())
            lore.add(plugin.texts().component(player, "map-tile-line", plugin.oreName(player, ore.getKey()), ore.getValue()));
        lore.add(plugin.texts().component(player, "map-tile-chunk", tile.chunkX(), tile.chunkZ()));
        Component name = shown == null
                ? plugin.texts().component(player, here ? "map-here-empty" : "map-empty")
                : plugin.texts().component(player, here ? "map-here" : "map-tile", plugin.oreName(player, shown), tile.count(shown));
        ItemStack stack = item(shown == null ? (here ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE) : OreMap.icon(shown), name, lore);
        if (shown != null) stack.setAmount(OreMap.amount(tile.count(shown)));
        if (here && shown != null) core.compat().platform().editMeta(stack, meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        core.compat().platform().editMeta(stack, meta -> {
            CoreComponents.displayName(core, meta, name.decoration(TextDecoration.ITALIC, false));
            CoreComponents.lore(core, meta, lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return stack;
    }
}

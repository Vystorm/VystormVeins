package at.esoren.vystormveins;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.schedule.TaskGroupInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;

/** Main-thread world access; generation workers submit immutable coordinates only. */
final class Regrowth implements AutoCloseable {
    record Batch(String world, List<Generation.OreCell> cells) {}
    record Entry(RegrowthState.Vein vein, RegrowthState.Cell cell) {}
    private final VeinsPlugin plugin;
    private final TaskGroup tasks;
    private final VersionedStore<RegrowthState> store;
    private final ArrayBlockingQueue<Batch> pending = new ArrayBlockingQueue<>(128);
    private final Map<String, Entry> index = new HashMap<>();
    private final ArrayDeque<RegrowthState.Vein> rotation = new ArrayDeque<>();
    private RegrowthState state;
    private volatile boolean enabled, overflow;
    private volatile Set<String> worlds = Set.of();
    private long lastSave;
    private volatile RegrowthOptions defaults = new RegrowthOptions(false, 60000, true, RegrowthOptions.DEFAULT_MESSAGE);
    private volatile Map<String, RegrowthOptions> options = Map.of();
    private int maxCells;
    private boolean dirty;
    /** The tracking limit was reached and said once on the console; cleared by a reload (configure). */
    private boolean limitHit, limitReported;

    Regrowth(VeinsPlugin plugin, AtomicFilesInterface files, TaskGroupInterface taskGroups) {
        this.plugin = plugin;
        Path path = plugin.getDataFolder().toPath().resolve("regrowth.json");
        store = new VersionedStore<>(new VersionedStore.Backend() {
            public String read() {
                try {
                    return Files.exists(path) ? Files.readString(path) : null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            public void write(String text) {
                try {
                    files.write(path, text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }, RegrowthState.class, 1, Map.of(), RegrowthState::new, null);
        // A malformed or unknown store aborts this feature; never overwrite it with an empty state.
        state = store.load();
        validate(state);
        for (var vein : state.veins) addIndex(vein);
        tasks = taskGroups.of(plugin, "ore-regrowth");
        tasks.timer(20, 20, this::tick);
    }
    static void validate(RegrowthState state) {
        if (state == null || state.veins == null) throw new IllegalStateException("Invalid regrowth state");
        Set<String> positions = new HashSet<>();
        for (var vein : state.veins) {
            if (vein == null || vein.world == null || vein.cells == null || vein.cells.isEmpty()) throw new IllegalStateException("Invalid vein");
            UUID.fromString(vein.world);
            for (var cell : vein.cells) {
                Material material = cell == null ? null : Material.matchMaterial(cell.material);
                if (material == null || air(material) || cell.state < 0 || cell.state > 2
                        || !positions.add(vein.world + ":" + cell.key())) throw new IllegalStateException("Invalid or duplicate vein cell");
            }
            vein.checkSeeds();
        }
    }
    void configure(Settings settings) {
        defaults = RegrowthOptions.defaults(plugin.getConfig());
        Map<String, RegrowthOptions> overrides = new HashMap<>();
        for (var rule : settings.rules()) overrides.put(rule.id(), RegrowthOptions.read(plugin.getConfig(), "deposits." + rule.id() + ".regrowth", defaults));
        options = Map.copyOf(overrides);
        enabled = defaults.enabled() || options.values().stream().anyMatch(RegrowthOptions::enabled);
        worlds = settings.worlds();
        maxCells = Math.clamp(plugin.getConfig().getInt("regrowth.max-tracked-blocks", 100000), 100, 1000000);
        limitReported = false;
    }
    /** {@code regrowth.max-tracked-blocks} as applied (100 to 1,000,000). */
    int maxCells() {
        return maxCells;
    }
    boolean enabled() {
        return enabled;
    }
    /** Main thread only: tracked veins, of which still renewable, and tracked original cells. */
    int[] counts() {
        drain();
        int live = (int)state.veins.stream().filter(v -> !v.exhausted).count();
        return new int[]{state.veins.size(), live, index.size()};
    }
    /** Main thread only: true when breaking this block would exhaust a renewable vein (vein mining leaves it standing). */
    boolean lastSeed(Block block) {
        drain();
        Entry entry = entry(block);
        return enabled && entry != null && options(entry.vein.deposit).enabled() && entry.vein.lastSeed(entry.cell);
    }
    private RegrowthOptions options(String deposit) {
        return deposit == null || deposit.isEmpty() ? defaults : options.getOrDefault(deposit, new RegrowthOptions(false, 60000, false, ""));
    }
    void generated(UUID world, List<Generation.OreCell> cells) {
        if (!enabled) return;
        var renewable = cells.stream().filter(c -> options(c.deposit()).enabled()).toList();
        if (!renewable.isEmpty() && (renewable.size() > 8192 || !pending.offer(new Batch(world.toString(), renewable)))) overflow = true;
    }
    private String key(String world, Block b) {
        return world + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }
    private Entry entry(Block block) {
        return index.get(key(block.getWorld().getUID().toString(), block));
    }
    private void addIndex(RegrowthState.Vein vein) {
        for (var cell : vein.cells) index.put(vein.world + ":" + cell.key(), new Entry(vein, cell));
        if (!vein.exhausted) rotation.add(vein);
    }
    private void drain() {
        Batch batch;
        while ((batch = pending.poll()) != null) {
            for (var vein : RegrowthState.partition(batch.world(), batch.cells())) {
                // Never reset a previously tracked position (including exhausted veins) with /apply. That is not a
                // limit: before 1.22.2 it was reported as "tracking limit reached" on every /vveins apply.
                if (vein.cells.stream().anyMatch(c -> index.containsKey(vein.world + ":" + c.key()))) continue;
                if (vein.cells.size() > 4096) {
                    overflow = true;
                    continue;
                }
                if (index.size() + vein.cells.size() > maxCells) {
                    limitHit = true;
                    continue;
                }
                vein.nextGrowth = System.currentTimeMillis() + options(vein.deposit).intervalMillis();
                state.veins.add(vein);
                addIndex(vein);
                dirty = true;
            }
        }
        if (overflow) {
            overflow = false;
            plugin.getLogger().warning("Regrowth could not take every new vein at once (very large vein or a full queue): some new veins remain non-renewable. Existing records are preserved.");
        }
        if (limitHit) {
            limitHit = false;
            // Once per start or reload: with 20 players exploring this came every second before 1.22.2.
            if (!limitReported) {
                limitReported = true;
                plugin.getLogger().warning("Regrowth tracking limit reached: " + index.size() + " of " + maxCells
                        + " original blocks are tracked (regrowth.max-tracked-blocks, at most 1000000). Newly found veins stay"
                        + " non-renewable; existing records are preserved. Shown once until the next start or /vveins reload.");
            }
        }
    }
    private void removed(Block block, boolean replaced) {
        removed(entry(block), replaced);
    }
    private void removed(Entry entry, boolean replaced) {
        if (entry == null || entry.vein.exhausted) return;
        if (replaced) entry.cell.state = 2;
        else if (entry.cell.state == 0) entry.cell.state = 1;
        entry.vein.checkSeeds();
        dirty = true;
    }
    /**
     * Block break and place are registered by what they do (see {@link EventOrder}): ordered by Vystorm Core when it
     * is installed, plain Bukkit listeners otherwise. Both only keep the regrowth state: OBSERVE,
     * the final outcome.
     */
    void handleBlockEvents(EventOrder order) {
        order.handle(BlockBreakEvent.class, EventOrder.Stage.OBSERVE, this::broken);
        order.handle(BlockPlaceEvent.class, EventOrder.Stage.OBSERVE, this::placed);
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        drain();
        removed(event.getBlock(), false);
        Entry entry = entry(event.getBlock());
        if (enabled && worlds.contains(event.getBlock().getWorld().getName()) && entry != null
                && options(entry.vein.deposit).enabled() && entry.vein.warningDue()) {
            var settings = options(entry.vein.deposit);
            var player = event.getPlayer();
            long remaining = entry.vein.cells.stream().filter(c -> c.state == 0).count();
            if (RegrowthOptions.languageText(settings.message())) plugin.say(player, "regrowth-warning", entry.vein.required(), remaining, Objects.toString(entry.vein.deposit, ""));
            else if (!settings.message().isBlank()) player.sendMessage(settings.message()
                    .replace("{minimum}", String.valueOf(entry.vein.required()))
                    .replace("{remaining}", String.valueOf(entry.vein.cells.stream().filter(c -> c.state == 0).count()))
                    .replace("{node}", Objects.toString(entry.vein.deposit, "")));
            if (settings.sound()) {
                player.playSound(player.getLocation(), "minecraft:block.note_block.bass", 0.8f, 0.6f);
                UUID origin = player.getWorld().getUID();
                for (long delay : new long[]{6, 12}) tasks.later(delay, () -> {
                    if (enabled && options(entry.vein.deposit).enabled() && options(entry.vein.deposit).sound()
                            && player.isOnline() && player.getWorld().getUID().equals(origin))
                        player.playSound(player.getLocation(), "minecraft:block.note_block.bass", 0.8f, 0.6f);
                });
            }
            dirty = true;
        }
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void placed(BlockPlaceEvent event) {
        if (event.isCancelled() || !event.canBuild()) return;
        drain();
        removed(event.getBlockPlaced(), true);
        if (event instanceof BlockMultiPlaceEvent multi) for (var block : multi.getReplacedBlockStates()) removed(block.getBlock(), true);
    }
    /** Explosions and pistons only change the regrowth state: OBSERVE, the final outcome (see {@link EventOrder}). */
    void handleSharedEvents(EventOrder order) {
        order.handle(EntityExplodeEvent.class, EventOrder.Stage.OBSERVE, (EntityExplodeEvent event) -> exploded(event));
        order.handle(BlockExplodeEvent.class, EventOrder.Stage.OBSERVE, (BlockExplodeEvent event) -> exploded(event));
        order.handle(BlockPistonExtendEvent.class, EventOrder.Stage.OBSERVE, (BlockPistonExtendEvent event) -> piston(event));
        order.handle(BlockPistonRetractEvent.class, EventOrder.Stage.OBSERVE, (BlockPistonRetractEvent event) -> piston(event));
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void exploded(EntityExplodeEvent event) {
        if (!event.isCancelled()) {
            drain();
            event.blockList().forEach(b -> removed(b, false));
        }
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void exploded(BlockExplodeEvent event) {
        if (!event.isCancelled()) {
            drain();
            event.blockList().forEach(b -> removed(b, false));
        }
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void piston(BlockPistonExtendEvent event) {
        if (!event.isCancelled()) moved(event.getBlocks(), event.getDirection());
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void piston(BlockPistonRetractEvent event) {
        if (!event.isCancelled()) moved(event.getBlocks(), event.getDirection());
    }
    private void moved(List<Block> blocks, org.bukkit.block.BlockFace direction) {
        drain();
        for (Block block : blocks) {
            removed(block, true);
            removed(index.get(block.getWorld().getUID() + ":" + (block.getX() + direction.getModX()) + ","
                    + (block.getY() + direction.getModY()) + "," + (block.getZ() + direction.getModZ())), true);
        }
    }
    private void tick() {
        drain();
        long now = System.currentTimeMillis();
        if (enabled) {
            int budget = 8192, count = rotation.size();
            while (count-->0 && budget > 0) {
                var vein = rotation.remove();
                budget -= vein.cells.size();
                if (!vein.exhausted && now >= vein.nextGrowth) grow(vein, now);
                if (!vein.exhausted) rotation.add(vein);
            }
        }
        if (dirty && now - lastSave >= 60000) {
            store.save(state);
            dirty = false;
            lastSave = now;
        }
    }
    private void grow(RegrowthState.Vein vein, long now) {
        if (vein.exhausted || !options(vein.deposit).enabled()) return;
        World world = Bukkit.getWorld(UUID.fromString(vein.world));
        if (world == null || !worlds.contains(world.getName())) return;
        for (var cell : vein.cells) if (!world.isChunkLoaded(cell.x>>4, cell.z>>4)) return;
        // Reconcile external changes before testing the original 10% threshold.
        for (var cell : vein.cells) {
            if (cell.state == 0 && world.getBlockAt(cell.x, cell.y, cell.z).getType() != Material.matchMaterial(cell.material)) {
                cell.state = 2;
                dirty = true;
            }
        }
        vein.checkSeeds();
        if (vein.exhausted) {
            dirty = true;
            return;
        }
        vein.nextGrowth = now + options(vein.deposit).intervalMillis();
        dirty = true;
        for (int i = 0; i < vein.cells.size(); i++) {
            int slot = Math.floorMod(vein.cursor++, vein.cells.size());
            var cell = vein.cells.get(slot);
            if (cell.state != 1) continue;
            Block block = world.getBlockAt(cell.x, cell.y, cell.z);
            if (!air(block.getType())) {
                cell.state = 2;
                continue;
            }
            // Do not materialize a solid block inside an entity.
            if (!world.getNearbyEntities(new org.bukkit.util.BoundingBox(cell.x, cell.y, cell.z, cell.x + 1, cell.y + 1, cell.z + 1)).isEmpty()) continue;
            BlockSpreadEvent event = new BlockSpreadEvent(block, seedBlock(world, vein), block.getState());
            event.getNewState().setType(Material.matchMaterial(cell.material));
            plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled() || !air(block.getType()) || event.getNewState().getType() != Material.matchMaterial(cell.material)) continue;
            block.setType(Material.matchMaterial(cell.material), false);
            cell.state = 0;
            break;
        }
    }
    private Block seedBlock(World world, RegrowthState.Vein vein) {
        var seed = vein.cells.stream().filter(c -> c.state == 0).findFirst().orElseThrow();
        return world.getBlockAt(seed.x, seed.y, seed.z);
    }
    private static boolean air(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }
    @Override public void close() {
        enabled = false;
        tasks.close();
        drain();
        if (dirty) store.save(state);
    }
}

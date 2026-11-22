package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.persistence.*;

/** Personal discovery journal. Uses Bukkit player/chunk data, including without Core. */
final class MiningJournal {
    static final List<String> FAMILIES = List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald", "nether_quartz", "ancient_debris");
    static final long[] GOALS ={1, 16, 64, 256};
    static final int MAX_POSITIONS = 4096;
    private static final NamespacedKey COUNTS = new NamespacedKey("vystormveins", "journal_counts");
    private static final NamespacedKey SEEN = new NamespacedKey("vystormveins", "journal_positions");
    private static final NamespacedKey FULL = new NamespacedKey("vystormveins", "journal_full");
    private final VeinsPlugin plugin;
    private final Set<Position> pending = new HashSet<>();
    private record Position(UUID world, int x, int y, int z) {}
    MiningJournal(VeinsPlugin plugin) {
        this.plugin = plugin;
    }
    static String family(Material material) {
        String family = Prospecting.family(material.getKey().toString(), Set.of());
        if ("nether_gold".equals(family)) family = "gold";
        return family != null && FAMILIES.contains(family) ? family : null;
    }
    static long[] counts(PersistentDataContainer data) {
        long[] stored = data.get(COUNTS, PersistentDataType.LONG_ARRAY), result = new long[FAMILIES.size()];
        if (stored != null) for (int i = 0; i < Math.min(stored.length, result.length); i++) result[i] = Math.max(0, stored[i]);
        return result;
    }
    /** All ore blocks in the journal (saturating). */
    static long total(long[] counts) {
        long total = 0;
        for (long value : counts) total = value > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + Math.max(0, value);
        return total;
    }
    static int tier(long count) {
        int level = 0;
        for (long goal : GOALS) if (count >= goal) level++;
        return level;
    }
    static int discovered(long[] counts) {
        int n = 0;
        for (long count : counts) if (count > 0) n++;
        return n;
    }
    static int collectionTier(int found) {
        return found >= 10 ? 3 : found >= 6 ? 2 : found >= 3 ? 1 : 0;
    }
    static int coordinate(Block block) {
        return (block.getY()<<8) | ((block.getX() & 15)<<4) | (block.getZ() & 15);
    }
    /** Fail closed when a chunk's bounded ledger is full; never erase a placement/discovery marker. */
    static boolean mark(PersistentDataContainer data, int position) {
        if (data.has(FULL, PersistentDataType.BYTE)) return false;
        int[] values = data.getOrDefault(SEEN, PersistentDataType.INTEGER_ARRAY, new int[0]);
        for (int value : values) if (value == position) return false;
        if (values.length >= MAX_POSITIONS) {
            data.set(FULL, PersistentDataType.BYTE, (byte)1);
            return false;
        }
        int[] next = Arrays.copyOf(values, values.length + 1);
        next[values.length] = position;
        data.set(SEEN, PersistentDataType.INTEGER_ARRAY, next);
        return true;
    }
    static boolean seen(PersistentDataContainer data, int position) {
        if (data.has(FULL, PersistentDataType.BYTE)) return true;
        for (int value : data.getOrDefault(SEEN, PersistentDataType.INTEGER_ARRAY, new int[0])) if (value == position) return true;
        return false;
    }
    /**
     * Block break and place are registered by what they do (see {@link EventOrder}): ordered by Vystorm Core when it
     * is installed, plain Bukkit listeners otherwise. Both only keep the journal: OBSERVE, the final
     * outcome.
     */
    void handleBlockEvents(EventOrder order) {
        order.handle(BlockPlaceEvent.class, EventOrder.Stage.OBSERVE, this::placed);
        order.handle(BlockBreakEvent.class, EventOrder.Stage.OBSERVE, this::broken);
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void placed(BlockPlaceEvent event) {
        if (family(event.getBlockPlaced().getType()) != null) markBlock(event.getBlockPlaced());
    }
    private void markBlock(Block block) {
        if (block.getWorld().isChunkLoaded(block.getX()>>4, block.getZ()>>4)) mark(block.getChunk().getPersistentDataContainer(), coordinate(block));
    }
    /** Pistons that move ore are only noted in the journal: OBSERVE, the final outcome (see {@link EventOrder}). */
    void handleSharedEvents(EventOrder order) {
        order.handle(BlockPistonExtendEvent.class, EventOrder.Stage.OBSERVE, this::extended);
        order.handle(BlockPistonRetractEvent.class, EventOrder.Stage.OBSERVE, this::retracted);
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void extended(BlockPistonExtendEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }
    /* Stage OBSERVE, registered in handleSharedEvents */
    public void retracted(BlockPistonRetractEvent event) {
        moved(event.getBlocks(), event.getDirection());
    }
    private void moved(List<Block> blocks, org.bukkit.block.BlockFace direction) {
        for (Block block : blocks) if (family(block.getType()) != null) {
            markBlock(block);
            markBlock(block.getRelative(direction));
        }
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String family = family(block.getType());
        if (!VeinsPlugin.enabled(plugin, Feature.JOURNAL)) return; // mining-journal.enabled off: nothing is counted
        if (family == null || player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission("vystormveins.journal")) return;
        if (seen(block.getChunk().getPersistentDataContainer(), coordinate(block)) || pending.size() >= 1024) return;
        Material original = block.getType();
        Position position = new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (!pending.add(position)) return;
        // Observe the completed break, including a cancellation by a later listener. No chunk loads or scans.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(position);
            if (event.isCancelled() || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                    || !block.getWorld().isChunkLoaded(block.getX()>>4, block.getZ()>>4) || block.getType() == original) return;
            if (!mark(block.getChunk().getPersistentDataContainer(), coordinate(block))) return;
            credit(player, family);
        });
    }
    void credit(Player player, String family) {
        int index = FAMILIES.indexOf(family);
        if (index < 0) return;
        long[] values = counts(player.getPersistentDataContainer());
        long before = values[index];
        int beforeCollection = collectionTier(discovered(values));
        values[index] = before == Long.MAX_VALUE ? before : before + 1;
        player.getPersistentDataContainer().set(COUNTS, PersistentDataType.LONG_ARRAY, values);
        plugin.journalChanged(player, values);
        if (tier(values[index]) > tier(before)) plugin.say(player, "journal-unlocked", plugin.oreName(player, family), values[index], tier(values[index]));
        if (collectionTier(discovered(values)) > beforeCollection) plugin.say(player, "journal-collection", discovered(values), FAMILIES.size());
    }
    void show(CommandSender sender) {
        if (!sender.hasPermission("vystormveins.journal")) {
            plugin.say(sender, "denied");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.say(sender, "player-only");
            return;
        }
        long[] values = counts(player.getPersistentDataContainer());
        long total = 0;
        for (long value : values) total = value > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + value;
        plugin.say(player, "journal-header", total, discovered(values), FAMILIES.size());
        for (int i = 0; i < values.length; i++) {
            int tier = tier(values[i]);
            String next = tier == GOALS.length ? plugin.word(player, "journal-complete") : Long.toString(GOALS[tier]);
            plugin.say(player, "journal-line", plugin.oreName(player, FAMILIES.get(i)), values[i], tier, GOALS.length, next);
        }
        plugin.say(player, "journal-goals", collectionTier(discovered(values)));
        plugin.say(player, "journal-rules");
    }
}

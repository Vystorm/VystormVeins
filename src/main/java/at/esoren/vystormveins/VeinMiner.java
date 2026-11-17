package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * Sneak-mining an ore with a fitting pickaxe also mines the rest of the vein. Every extra block goes through
 * Player#breakBlock, so protection plugins, drops, enchantments, tool wear, experience and regrowth tracking all see
 * a normal block break. With {@code collect-drops} the items and experience of the extra blocks land where the first
 * ore was broken instead of all over the cave.
 */
final class VeinMiner implements Listener {
    private final VeinsPlugin plugin;
    /** Players whose extra blocks are being broken right now; their own break events must not start another vein. */
    private final Set<UUID> busy = new HashSet<>();
    /** While busy with collect-drops: where the drops go and the experience gathered so far. */
    private final Map<UUID, Location> collectAt = new HashMap<>();
    private final Map<UUID, Integer> collectedXp = new HashMap<>();

    VeinMiner(VeinsPlugin plugin) { this.plugin = plugin; }

    /**
     * Block break and place are registered by what they do (see {@link EventOrder}): ordered by Vystorm Core when it
     * is installed, plain Bukkit listeners otherwise. Vein mining starts one tick after a break
     * that really happened and never touches that event: OBSERVE. The experience of the extra blocks is collected after
     * every other plugin has set it: FINISH.
     */
    void handleBlockEvents(EventOrder order) {
        order.handle(BlockBreakEvent.class, EventOrder.Stage.OBSERVE, this::broken);
        order.handle(BlockBreakEvent.class, EventOrder.Stage.FINISH, this::extraBroken);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent event) {
        Player player = event.getPlayer();
        VeinMining.Options options = plugin.veinMining();
        if (!options.enabled() || busy.contains(player.getUniqueId()) || player.getGameMode() != GameMode.SURVIVAL
                || (options.sneak() && !player.isSneaking()) || !player.hasPermission("vystormveins.veinmine")
                || !VeinMining.playerEnabled(player)) return;
        Block origin = event.getBlock();
        Set<String> extra = plugin.depositOres();
        String family = Prospecting.family(origin.getType().getKey().toString(), extra);
        if (family == null || options.excluded().contains(family)) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!origin.isPreferredTool(tool)) return;
        World world = origin.getWorld();
        List<int[]> vein = VeinMining.collect((x, y, z) -> world.isChunkLoaded(x >> 4, z >> 4)
                        ? world.getBlockAt(x, y, z).getType().getKey().toString() : null,
                origin.getX(), origin.getY(), origin.getZ(), family, extra, options.maxBlocks(), world.getMinHeight(), world.getMaxHeight());
        if (vein.isEmpty()) return;
        Location drop = origin.getLocation().add(0.5, 0.5, 0.5);
        // Next tick: the first block is gone and every plugin has seen its break before the rest follows.
        plugin.getServer().getScheduler().runTask(plugin, () -> mine(player, world, family, extra, vein, options, drop));
    }

    /** Extra blocks of a vein (collect-drops): their experience is gathered and spawned once at the first block. Stage FINISH. */
    public void extraBroken(BlockBreakEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!collectAt.containsKey(id) || event.getExpToDrop() <= 0) return;
        collectedXp.merge(id, event.getExpToDrop(), Integer::sum);
        event.setExpToDrop(0);
    }

    /** Extra blocks of a vein (collect-drops): their items fall where the first block was. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extraDrops(BlockDropItemEvent event) {
        Location at = collectAt.get(event.getPlayer().getUniqueId());
        if (at == null || !at.getWorld().equals(event.getBlock().getWorld())) return;
        for (Item item : event.getItems()) { item.teleport(at); item.setVelocity(new org.bukkit.util.Vector()); }
    }

    private void mine(Player player, World world, String family, Set<String> extra, List<int[]> vein, VeinMining.Options options, Location drop) {
        if (!player.isOnline() || !player.getWorld().equals(world) || player.getGameMode() != GameMode.SURVIVAL
                || !plugin.veinMining().enabled() || !player.hasPermission("vystormveins.veinmine")
                || !VeinMining.playerEnabled(player)) return;
        Regrowth regrowth = options.respectRegrowth() ? plugin.regrowth() : null;
        int mined = 0, kept = 0;
        boolean toolStop = false;
        MiningSafety.Hazard hazard = null;
        int[] stoppedAt = null;
        UUID id = player.getUniqueId();
        busy.add(id);
        if (options.collectDrops() && world.isChunkLoaded(drop.getBlockX() >> 4, drop.getBlockZ() >> 4)) collectAt.put(id, drop);
        try {
            for (int[] at : vein) {
                if (!world.isChunkLoaded(at[0] >> 4, at[2] >> 4)) break;
                Block block = world.getBlockAt(at[0], at[1], at[2]);
                if (!family.equals(Prospecting.family(block.getType().getKey().toString(), extra))) continue;
                ItemStack tool = player.getInventory().getItemInMainHand();
                if (!block.isPreferredTool(tool)) break;
                if (options.protectTool() && tool.getItemMeta() instanceof Damageable damage
                        && !damage.isUnbreakable() && VeinMining.toolAtLimit(damage.getDamage(),
                                damage.hasMaxDamage() ? damage.getMaxDamage() : tool.getType().getMaxDurability())) { toolStop = true; break; }
                if (regrowth != null && regrowth.lastSeed(block)) { kept++; continue; }
                hazard = MiningSafety.check(MiningSafety.world(world), at[0], at[1], at[2], world.getMaxHeight(), plugin.miningSafety());
                if (hazard != null) { stoppedAt = at; break; }
                if (player.breakBlock(block)) mined++;
            }
        } finally {
            busy.remove(id);
            collectAt.remove(id);
            Integer xp = collectedXp.remove(id);
            if (xp != null && xp > 0) world.spawn(drop, ExperienceOrb.class, orb -> orb.setExperience(xp));
        }
        if (kept > 0) plugin.say(player, "veinmine-regrowth", kept);
        if (toolStop) plugin.say(player, "veinmine-tool", mined);
        if (hazard != null) plugin.say(player, "safety-stopped", mined,
                plugin.word(player, "safety-" + hazard.name().toLowerCase(Locale.ROOT)), stoppedAt[0], stoppedAt[1], stoppedAt[2]);
    }
}

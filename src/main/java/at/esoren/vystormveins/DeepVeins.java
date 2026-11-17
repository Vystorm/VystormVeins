package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Deep veins: rare, large veins far down (generated as the extra deposit {@code deep-vein}) whose core blocks can only
 * be mined while enough players are close together. Every miner nearby shares the reward: each gets the configured XP
 * for each core block. Core blocks a player placed are ordinary blocks (remembered until restart). Server thread.
 */
final class DeepVeins {
    /** Generated deposit id. */
    static final String DEPOSIT = "deep-vein";

    record Options(boolean enabled, Set<String> cores, int maxY, int players, int radius, int xp) {
        static Options read(ConfigurationSection config) {
            Set<String> cores = new HashSet<>();
            for (String name : config.getStringList("deep-veins.core-blocks")) {
                Material material = Material.matchMaterial(name);
                if (material != null && material.isBlock()) cores.add(material.getKey().toString());
            }
            return new Options(config.getBoolean("deep-veins.enabled", true), Set.copyOf(cores),
                    config.getInt("deep-veins.max-y", -40), Math.clamp(config.getInt("deep-veins.players", 3), 1, 16),
                    Math.clamp(config.getInt("deep-veins.radius", 12), 2, 48), Math.clamp(config.getInt("deep-veins.xp-per-block", 15), 0, 1000));
        }
    }

    /** Adds the generated deep vein to {@code rules} when enabled and no deposit uses its id yet. */
    static void addRule(ConfigurationSection config, List<at.esoren.vystormveins.engine.VeinRule> rules, java.util.function.UnaryOperator<String> material) {
        if (!config.getBoolean("deep-veins.enabled", true) || rules.stream().anyMatch(r -> r.id().equals(DEPOSIT))) return;
        ConfigurationSection vein = config.getConfigurationSection("deep-veins.vein");
        if (vein == null) return;
        Set<String> hosts = new HashSet<>();
        for (String host : vein.getStringList("hosts")) hosts.add(material.apply(host));
        if (hosts.isEmpty()) return;
        String ore = material.apply(vein.getString("ore", "minecraft:ancient_debris"));
        rules.add(new at.esoren.vystormveins.engine.VeinRule(DEPOSIT, ore, null, hosts, Set.of(), vein.getInt("min-y", -64),
                vein.getInt("max-y", -48), 1, vein.getInt("size", 24), vein.getDouble("chance", 0.01)));
    }

    /** 1.18.0 shipped size 48 / chance 0.02 (too generous per find); untouched values move to 24 / 0.01. */
    static boolean migrateDefaults(ConfigurationSection config) {
        if (config.getInt("deep-veins.vein.size", -1) != 48 || Math.abs(config.getDouble("deep-veins.vein.chance", -1) - 0.02) > 1e-9) return false;
        config.set("deep-veins.vein.size", 24);
        config.set("deep-veins.vein.chance", 0.01);
        return true;
    }

    /** How many more miners are needed: 0 when {@code nearby} (including the breaker) is enough. */
    static int missing(int nearby, int required) { return Math.max(0, required - Math.max(0, nearby)); }

    private final VeinsPlugin plugin;
    private final Map<UUID, Set<Long>> placed = new HashMap<>();
    private Options options;

    DeepVeins(VeinsPlugin plugin) { this.plugin = plugin; apply(); }

    void apply() { options = Options.read(plugin.getConfig()); }
    Options options() { return options; }

    private boolean core(Block block) {
        Settings settings = plugin.settings();
        return options.enabled() && settings != null && settings.worlds().contains(block.getWorld().getName())
                && block.getY() <= options.maxY() && options.cores().contains(block.getType().getKey().toString())
                && !placed.getOrDefault(block.getWorld().getUID(), Set.of()).contains(MiningClaims.key(block.getX(), block.getY(), block.getZ()));
    }

    /**
     * Block break and place are registered by what they do (see {@link EventOrder}): ordered by Vystorm Core when it
     * is installed, plain Bukkit listeners otherwise. A deep vein core with too few
     * miners nearby is refused in ADJUST (a rule of this plugin, not a protection); the shared experience and the
     * player-placed cores follow the final outcome in OBSERVE.
     */
    void handleBlockEvents(EventOrder order) {
        order.handle(BlockPlaceEvent.class, EventOrder.Stage.OBSERVE, this::place);
        order.handle(BlockBreakEvent.class, EventOrder.Stage.ADJUST, this::mine);
        order.handle(BlockBreakEvent.class, EventOrder.Stage.OBSERVE, this::mined);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void place(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (options.cores().contains(block.getType().getKey().toString()))
            placed.computeIfAbsent(block.getWorld().getUID(), w -> new HashSet<>()).add(MiningClaims.key(block.getX(), block.getY(), block.getZ()));
    }

    /** Stage ADJUST, registered in {@link #handleBlockEvents}: a deep vein core needs enough miners nearby. */
    public void mine(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!core(block)) return;
        Player breaker = event.getPlayer();
        if (breaker.getGameMode() == GameMode.CREATIVE) return;
        List<Player> team = team(block, breaker);
        int missing = missing(team.size(), options.players());
        if (missing > 0) {
            event.setCancelled(true);
            plugin.sayBar(breaker, "deep-needs", options.players(), team.size(), missing);
        }
    }

    /** The breaker and every player close enough to count. */
    private List<Player> team(Block block, Player breaker) {
        List<Player> team = nearby(block.getLocation().add(0.5, 0.5, 0.5));
        if (!team.contains(breaker)) team.add(breaker);
        return team;
    }

    /**
     * Stage OBSERVE, registered in {@link #handleBlockEvents}: the break really happens. The team shares the
     * experience of a core; any other block is no longer remembered as a player-placed core.
     */
    public void mined(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!core(block)) {
            Set<Long> own = placed.get(block.getWorld().getUID());
            if (own != null) own.remove(MiningClaims.key(block.getX(), block.getY(), block.getZ()));
            return;
        }
        Player breaker = event.getPlayer();
        if (breaker.getGameMode() == GameMode.CREATIVE) return;
        List<Player> team = team(block, breaker);
        // only if the rule in mine() was met; another plugin may have let the break through against it
        if (missing(team.size(), options.players()) > 0) return;
        for (Player miner : team) {
            if (options.xp() > 0) miner.giveExp(options.xp());
            plugin.sayBar(miner, "deep-shared", options.xp(), team.size());
        }
        block.getWorld().playSound(block.getLocation(), "minecraft:block.amethyst_cluster.break", 1f, 0.6f);
    }

    private List<Player> nearby(Location at) {
        double r2 = (double) options.radius() * options.radius();
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers())
            if (p.getGameMode() != GameMode.SPECTATOR && !p.isDead() && p.getLocation().distanceSquared(at) <= r2) out.add(p);
        return out;
    }
}

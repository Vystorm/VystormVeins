package at.esoren.vystormveins;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.generator.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Standalone one-server plugin; no classes or runtime dependency from OreVeins. */
public final class VeinsPlugin extends JavaPlugin implements Listener, TabExecutor {
    private VeinsTexts texts;
    /** The link to Vystorm Core; null without Core or with a Core older than 0.64.0 (Core features off). */
    private CoreLink core;
    /** Registers the block break and place handlers: through Core's stages with Core, as Bukkit listeners without. */
    private EventOrder eventOrder;
    /** Core version the optional Core features need; a plain constant so that this class links without Core. */
    static final String REQUIRED_CORE = "0.64.0";
    private volatile Settings settings;
    /** Every feature switch as read from config.yml; null (= defaults) until then and in tests that mock the plugin. */
    private volatile Feature.Switches switches;
    /** The switches online players last got their command list for (null without Core or before the first adoption). */
    private Feature.Switches commandSwitches;

    /** Whether a feature (and the switch it needs) is on; static so classes holding a mocked plugin read the real field. */
    static boolean enabled(VeinsPlugin plugin, Feature feature) {
        Feature.Switches current = plugin == null ? null : plugin.switches;
        return (current == null ? Feature.Switches.read(null) : current).on(feature);
    }

    boolean on(Feature feature) { return enabled(this, feature); }

    /** True (and the shared message sent) when the feature is off; names the needed switch when only that one is off. */
    boolean off(CommandSender sender, Feature feature) {
        if (on(feature)) return false;
        Feature.Switches current = switches;
        if (feature.needs != null && current != null && !current.off().contains(feature))
            say(sender, "feature-needs", feature.needs.path, texts == null ? feature.needs.path : texts.word(sender, feature.needs.label()));
        else say(sender, "feature-off");
        return true;
    }
    private volatile Regrowth regrowth;
    private volatile Prospecting.Options prospecting = Prospecting.Options.DEFAULTS;
    private volatile VeinMining.Options veinMining = VeinMining.Options.DEFAULTS;
    private final MiningClaims claims = new MiningClaims();
    private volatile OreMap.Options oreMap = OreMap.Options.DEFAULTS;
    private volatile MiningSafety.Options miningSafety = MiningSafety.Options.DEFAULTS;
    private OreMapMenu oreMapMenu;
    private MiningJournal journal;
    private final Map<UUID, Long> prospectCooldowns = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final LongAdder generated = new LongAdder(), placed = new LongAdder();
    private final BlockPopulator populator = new BlockPopulator() {
        @Override public void populate(WorldInfo info, Random ignored, int x, int z, LimitedRegion region) {
            Settings snapshot = settings;
            if (snapshot == null || !on(Feature.GENERATION) || !snapshot.worlds().contains(info.getName())) return;
            Generation.Result result = Generation.run(Generation.region(region, info.getMinHeight(), info.getMaxHeight()),
                    info.getSeed(), x, z, snapshot, false, cells -> {
                        Regrowth tracker = regrowth;
                        if (tracker != null) tracker.generated(info.getUID(), cells);
                    });
            generated.increment();
            placed.add(result.placed());
        }
    };
    private record Preview(UUID world, int x, int z, Settings settings, long expires) {}
    @Override public void onEnable() {
        core = connectCore();
        if (core != null) {
            try {
                core.registerUpdates(this);
            } catch (RuntimeException noUpdateCheck) {
                getLogger().fine("Update check not registered: " + noUpdateCheck);
            }
        }
        saveDefaultConfig();
        if (core != null) {
            try {
                core.registerConfigMenu(this, () -> reloadCommand(getServer().getConsoleSender()));
            } catch (RuntimeException configMenuUnavailable) {
                getLogger().fine("Config menu unavailable: " + configMenuUnavailable);
            }
            try {
                if (getConfig().getBoolean(Feature.TUTORIAL.path, true)) core.registerTutorial(this); // read once at start
            } catch (RuntimeException noTutorial) {
                getLogger().fine("Tutorial not registered: " + noTutorial);
            }
        }
        try {
            var file = new java.io.File(getDataFolder(), "config.yml");
            var config = new YamlConfiguration();
            config.load(file);
            // switches added since 1.22.0 come into an existing config.yml with their comments (Core's merge stays off)
            boolean added = Feature.addMissingKeys(config, bundledConfig());
            if (MiningSafety.migrate(config) | added) config.save(file);
            reloadConfig();
            miningSafety = MiningSafety.Options.read(config);
        } catch (Exception invalid) {
            // the file is not changed; ore generation stays off rather than running with settings the admin did not write
            getLogger().severe("config.yml could not be loaded (" + reason(invalid) + "). The file was not changed; fix it and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        texts = new VeinsTexts(this, core);
        for (String preset : List.of("rare-large", "frequent-small", "mixed")) {
            String path = "presets/" + preset + ".yml";
            if (!new java.io.File(getDataFolder(), path).exists()) saveResource(path, false);
        }
        if (DeepVeins.migrateDefaults(getConfig())) {
            saveConfig();
            getLogger().info("Deep veins: the untouched 1.18.0 defaults (size 48, chance 0.02) were lowered to size 24, chance 0.01.");
        }
        try {
            settings = Settings.read(getConfig());
        } catch (RuntimeException error) {
            getLogger().severe("Invalid deposits: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            prospecting = Prospecting.Options.read(getConfig());
        } catch (RuntimeException error) {
            getLogger().warning("Invalid prospecting settings, using defaults: " + error.getMessage());
        }
        try {
            veinMining = VeinMining.Options.read(getConfig());
        } catch (RuntimeException error) {
            getLogger().warning("Invalid vein-mining settings, vein mining stays off: " + error.getMessage());
        }
        oreMap = OreMap.Options.read(getConfig());
        switches = Feature.Switches.read(getConfig());
        eventOrder = new EventOrder(this, core);
        getServer().getPluginManager().registerEvents(this, this);
        handleBlockEvents(eventOrder);
        handleSharedEvents(eventOrder);
        VeinMiner veinMiner = new VeinMiner(this);
        getServer().getPluginManager().registerEvents(veinMiner, this);
        veinMiner.handleBlockEvents(eventOrder);
        deepVeins = new DeepVeins(this);
        deepVeins.handleBlockEvents(eventOrder);
        journal = new MiningJournal(this);
        journal.handleBlockEvents(eventOrder);
        journal.handleSharedEvents(eventOrder);
        Objects.requireNonNull(getCommand("veinjournal")).setExecutor(this);
        Objects.requireNonNull(getCommand("veinjournal")).setTabCompleter(this);
        if (core != null) {
            try {
                oreMapMenu = core.oreMapMenu(this);
            } catch (RuntimeException unavailable) {
                getLogger().warning("Ore map unavailable with this Vystorm Core: " + unavailable);
            }
        }
        Objects.requireNonNull(getCommand("vveins")).setExecutor(this);
        Objects.requireNonNull(getCommand("vveins")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("veinmine")).setExecutor(this);
        Objects.requireNonNull(getCommand("veinmine")).setTabCompleter(this);
        if (core != null) {
            try {
                adoptCommands();
            } catch (RuntimeException unavailable) {
                getLogger().warning("Localized /vveins and /veinmap could not be registered in Vystorm Core (" + unavailable
                        + "); the English commands keep working.");
            }
            try {
                // values need Vystorm Core 0.65.0; with an older Core there are no placeholders and nothing else changes
                if (!core.registerPlaceholders(this)) getLogger().info("Placeholders need Vystorm Core 0.65.0 or newer; they are off.");
            } catch (RuntimeException | LinkageError unavailable) {
                getLogger().warning("Placeholders not registered: " + unavailable);
            }
            try {
                core.declareSwitches(this);
            } catch (RuntimeException | LinkageError unavailable) {
                getLogger().fine("Switches not listed in /vadmin features: " + unavailable);
            }
        }
        getServer().getWorlds().forEach(this::attach);
        configureRegrowth();
    }

    /** Core's command trees (with Core); the subcommands of switched-off features are hidden. */
    private void adoptCommands() {
        commandSwitches = switches == null ? Feature.Switches.read(null) : switches;
        core.adoptCommands(this);
    }

    /**
     * After a reload in which a switch changed: online players get their command list again. The trees are not
     * adopted again: with Core 0.65.0 they ask the switches at every completion; with an older Core help and tab
     * completion follow a switch after a restart.
     */
    private void applySwitches() {
        if (core == null || commandSwitches == null || commandSwitches.equals(switches)) return;
        commandSwitches = switches;
        try { core.refreshCommands(); }
        catch (RuntimeException | LinkageError unavailable) { getLogger().warning("Command lists could not be sent again: " + unavailable); }
    }

    /** The config.yml in the jar (defaults and their comments). */
    YamlConfiguration bundledConfig() {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = getResource("config.yml")) {
            if (in != null) defaults.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception unreadable) {
            getLogger().warning("Bundled config.yml unreadable: " + unreadable);
        }
        return defaults;
    }

    /** Journal counts of a player (placeholders). */
    long[] journalCounts(Player player) { return MiningJournal.counts(player.getPersistentDataContainer()); }

    /** The player's active mining claim, if any (placeholders). */
    Optional<MiningClaims.Claim> claimOf(UUID player) { return claims.of(player, System.currentTimeMillis()); }
    /**
     * Vystorm Core is a soft dependency: without Core, or with a Core that is too old for the managers, the
     * Core-based features stay off and the plugin keeps running.
     */
    private CoreLink connectCore() {
        if (!getServer().getPluginManager().isPluginEnabled("vystorm_core")) return null;
        try {
            return CoreLink.connect(this);
        } catch (RuntimeException | LinkageError unusable) {
            // A Core so old that it has no CoreApi at all, or one that is not running.
            getLogger().warning("Vystorm Core is installed but cannot be used (" + unusable + "); the Core features of VystormVeins need Vystorm Core "
                    + REQUIRED_CORE + " or newer and are switched off.");
            return null;
        }
    }
    private void configureRegrowth() {
        if (regrowth == null && core != null) {
            try {
                Regrowth tracker = core.regrowth(this);
                tracker.configure(settings);
                regrowth = tracker;
                tracker.handleBlockEvents(eventOrder);
                tracker.handleSharedEvents(eventOrder);
            } catch (RuntimeException failure) {
                getLogger().severe("Regrowth unavailable; its saved data remains untouched: " + failure);
            }
        }
        if (regrowth != null) regrowth.configure(settings);
        else if (RegrowthOptions.defaults(getConfig()).enabled() || settings.rules().stream().anyMatch(rule ->
                RegrowthOptions.read(getConfig(), "deposits." + rule.id() + ".regrowth", RegrowthOptions.defaults(getConfig())).enabled()))
            getLogger().warning("Regrowth requires Vystorm Core " + REQUIRED_CORE + " or newer. Generation remains available.");
    }
    private void attach(World world) {
        if (!world.getPopulators().contains(populator)) world.getPopulators().add(populator);
    }
    @EventHandler public void worldInit(WorldInitEvent event) {
        attach(event.getWorld());
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
        prospectCooldowns.remove(event.getPlayer().getUniqueId());
        claimNotices.remove(event.getPlayer().getUniqueId());
        if (oreMapMenu != null) oreMapMenu.forget(event.getPlayer().getUniqueId());
    }
    // ---- mining claims ----

    boolean claimsEnabled() {
        return on(Feature.CLAIMS);
    }
    int claimMaxBlocks() {
        return Math.clamp(getConfig().getInt("claims.max-blocks", 128), 1, 512);
    }
    int claimMinutes() {
        return Math.clamp(getConfig().getInt("claims.minutes", 20), 1, 240);
    }

    /**
     * The clicks with the prospecting rod are registered by what they do (see {@link EventOrder}): both are the
     * feature acting on the click (ACT), prospecting before claiming as before. Both also receive cancelled events,
     * because a click into the air arrives cancelled, and decide by their own checks.
     */
    void handleSharedEvents(EventOrder order) {
        order.handle(org.bukkit.event.player.PlayerInteractEvent.class, EventOrder.Stage.ACT, true, this::prospect);
        order.handle(org.bukkit.event.player.PlayerInteractEvent.class, EventOrder.Stage.ACT, true, this::claimVein);
    }
    /** Right-click (not sneaking) an ore with the prospecting rod: claims the connected vein. */
    /* Stage ACT, registered in handleSharedEvents */
    public void claimVein(org.bukkit.event.player.PlayerInteractEvent event) {
        Prospecting.Options options = prospecting;
        if (!claimsEnabled() || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.isSneaking() || event.getItem() == null || event.getItem().getType() != options.item() || event.getClickedBlock() == null
                || !player.hasPermission("vystormveins.claim")) return;
        org.bukkit.block.Block block = event.getClickedBlock();
        World world = block.getWorld();
        Set<String> extra = depositOres();
        String family = Prospecting.family(block.getType().getKey().toString(), extra);
        if (family == null) return;
        event.setCancelled(true);
        int max = claimMaxBlocks();
        List<int[]> found = VeinMining.collect((x, y, z) -> world.isChunkLoaded(x >> 4, z >> 4) ? world.getBlockAt(x, y, z).getType().getKey().toString() : null,
                block.getX(), block.getY(), block.getZ(), family, extra, max - 1, world.getMinHeight(), world.getMaxHeight());
        List<Long> blocks = new ArrayList<>();
        blocks.add(MiningClaims.key(block.getX(), block.getY(), block.getZ()));
        for (int[] p : found) blocks.add(MiningClaims.key(p[0], p[1], p[2]));
        long now = System.currentTimeMillis();
        int minutes = claimMinutes();
        MiningClaims.Result result = claims.claim(player.getUniqueId(), player.getName(), world.getUID(), family, blocks, now, now + minutes * 60_000L);
        if (result.code().equals("taken")) {
            MiningClaims.Claim other = result.claim();
            say(player, "claim-taken", other == null ? "?" : other.ownerName(), other == null ? 0 : Math.max(1, (other.until() - now + 59_999) / 60_000));
            return;
        }
        say(player, "claim-made", blocks.size(), oreName(player, family), minutes);
        player.playSound(player.getLocation(), "minecraft:block.amethyst_block.resonate", 0.8f, 1.2f);
    }

    /**
     * Block breaks are registered by what they do (see {@link EventOrder}): ordered by Vystorm Core when it is
     * installed, plain Bukkit listeners otherwise. A claim denies first (PROTECT) and shrinks only when the block was
     * really mined (OBSERVE).
     */
    void handleBlockEvents(EventOrder order) {
        order.handle(org.bukkit.event.block.BlockBreakEvent.class, EventOrder.Stage.PROTECT, this::claimedBreak);
        order.handle(org.bukkit.event.block.BlockBreakEvent.class, EventOrder.Stage.OBSERVE, this::claimMined);
    }

    /** Stage PROTECT, registered in {@link #handleBlockEvents}: others cannot mine a claimed vein (party members and admins can). */
    public void claimedBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (claims.size() == 0) return;
        org.bukkit.block.Block block = event.getBlock();
        long key = MiningClaims.key(block.getX(), block.getY(), block.getZ());
        long now = System.currentTimeMillis();
        var claim = claims.at(block.getWorld().getUID(), key, now);
        if (claim.isEmpty()) return;
        Player player = event.getPlayer();
        UUID owner = claim.get().owner();
        if (player.getUniqueId().equals(owner) || player.hasPermission("vystormveins.claim.bypass") || sameParty(owner, player.getUniqueId())) return;
        event.setCancelled(true);
        sayBar(player, "claim-blocked", claim.get().ownerName(), Math.max(1, (claim.get().until() - now + 59_999) / 60_000));
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}: a claimed block that was really mined shrinks the claim. */
    public void claimMined(org.bukkit.event.block.BlockBreakEvent event) {
        if (claims.size() == 0) return;
        org.bukkit.block.Block block = event.getBlock();
        long key = MiningClaims.key(block.getX(), block.getY(), block.getZ());
        if (claims.at(block.getWorld().getUID(), key, System.currentTimeMillis()).isPresent()) claims.mined(block.getWorld().getUID(), key);
    }

    private boolean sameParty(UUID a, UUID b) {
        if (!getConfig().getBoolean("claims.allow-party", true)) return false;
        if (core == null) return false;
        try {
            return core.sameParty(a, b);
        } catch (RuntimeException noParties) {
            return false;
        }
    }

    /** {@code /veinclaim [release]}. */
    void claimCommand(CommandSender sender, boolean release) {
        if (!(sender instanceof Player player)) {
            say(sender, "player-only");
            return;
        }
        if (!claimsEnabled()) {
            say(player, "feature-off");
            return;
        }
        if (release) {
            say(player, claims.release(player.getUniqueId()) ? "claim-released" : "claim-none");
            return;
        }
        long now = System.currentTimeMillis();
        var claim = claims.of(player.getUniqueId(), now);
        if (claim.isEmpty()) {
            say(player, "claim-none");
            return;
        }
        say(player, "claim-status", claim.get().blocks().size(), oreName(player, claim.get().family()), Math.max(1, (claim.get().until() - now + 59_999) / 60_000));
    }

    private final Map<UUID, Long> claimNotices = new HashMap<>();

    /** The "claimed" line at most every three seconds per player, so breaking attempts do not flood the chat. */
    private DeepVeins deepVeins;
    Settings settings() {
        return settings;
    }

    void sayBar(Player player, String key, Object... args) {
        long now = System.currentTimeMillis();
        Long last = claimNotices.get(player.getUniqueId());
        if (last != null && now - last < 3000) return;
        claimNotices.put(player.getUniqueId(), now);
        say(player, key, args);
    }

    /* Stage ACT, registered in handleSharedEvents */ public void prospect(org.bukkit.event.player.PlayerInteractEvent event) {
        Prospecting.Options options = prospecting;
        if (!options.enabled() || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (event.getItem() == null || event.getItem().getType() != options.item() || (options.sneak() && !player.isSneaking())
                || !player.hasPermission("vystormveins.prospect")) return;
        long now = System.currentTimeMillis();
        Long ready = prospectCooldowns.get(player.getUniqueId());
        if (ready != null && now < ready) {
            say(player, "prospect-cooldown", (ready - now + 999) / 1000);
            return;
        }
        prospectCooldowns.put(player.getUniqueId(), now + options.cooldownMillis());
        World world = player.getWorld();
        Location at = player.getLocation();
        Set<String> extra = depositOres();
        List<Prospecting.Find> finds = Prospecting.scan((x, y, z) -> world.isChunkLoaded(x >> 4, z >> 4)
                        ? world.getBlockAt(x, y, z).getType().getKey().toString() : null,
                at.getBlockX(), at.getBlockY(), at.getBlockZ(), options.radius(), world.getMinHeight(), world.getMaxHeight(), extra);
        finds = Prospecting.visible(finds, options.hidden());
        if (finds.isEmpty()) {
            say(player, "prospect-none", options.radius());
            player.playSound(at, "minecraft:block.amethyst_cluster.hit", 0.7f, 0.5f);
            return;
        }
        say(player, "prospect-header", options.radius());
        for (var find : finds.subList(0, Math.min(options.lines(), finds.size())))
            say(player, "prospect-line", oreName(player, find.family()), Math.round(find.distance()), direction(player, find), find.count());
        Prospecting.Find nearest = finds.getFirst();
        float pitch = (float) (0.6 + 1.2 * Math.max(0, 1 - nearest.distance() / options.radius()));
        player.playSound(at, "minecraft:block.amethyst_block.chime", 1f, pitch);
        // The ore position is fixed at scan time, so the later trail bursts still point at it after the player moves.
        int tx = at.getBlockX() + nearest.dx(), ty = at.getBlockY() + nearest.dy(), tz = at.getBlockZ() + nearest.dz();
        if (options.particles()) for (long delay : new long[]{0, 10, 20})
            getServer().getScheduler().runTaskLater(this, () -> trail(player, world, nearest.family(), tx, ty, tz), delay);
        // One click from the rod to the ore map instead of typing /veinmap.
        if (oreMapMenu != null && oreMap.enabled() && player.hasPermission("vystormveins.map")) oreMapMenu.hint(player);
    }
    /** Ore blocks of the configured deposits (normal and deep variants), for the rod, the map and vein mining. */
    Set<String> depositOres() {
        Set<String> extra = new HashSet<>();
        Settings snapshot = settings;
        if (snapshot != null) for (var rule : snapshot.rules()) {
            extra.add(rule.ore());
            if (rule.deepOre() != null) extra.add(rule.deepOre());
        }
        return extra;
    }
    VeinMining.Options veinMining() {
        return veinMining;
    }
    MiningSafety.Options miningSafety() {
        return miningSafety;
    }
    OreMap.Options oreMap() {
        return oreMap;
    }
    Prospecting.Options prospectingOptions() {
        return prospecting;
    }
    Regrowth regrowth() {
        return regrowth;
    }
    VeinsTexts texts() {
        return texts;
    }
    void journal(CommandSender sender) {
        if (off(sender, Feature.JOURNAL)) return;
        journal.show(sender);
    }
    private void trail(Player player, World world, String family, int tx, int ty, int tz) {
        if (!player.isOnline() || !player.getWorld().equals(world)) return;
        Location eye = player.getEyeLocation();
        double[] to = Prospecting.towards(eye.getX(), eye.getY(), eye.getZ(), tx, ty, tz);
        if (to == null) return;
        org.bukkit.util.Vector direction = new org.bukkit.util.Vector(to[0], to[1], to[2]);
        double length = to[3];
        Particle.DustOptions dust = new Particle.DustOptions(oreColor(family), 1.0f);
        for (double step = 0.8; step <= Math.min(4.0, length); step += 0.3)
            player.spawnParticle(Particle.DUST, eye.clone().add(direction.clone().multiply(step)), 1, 0, 0, 0, 0, dust);
    }
    String direction(Prospecting.Find find) {
        return direction(null, find);
    }
    String direction(CommandSender viewer, Prospecting.Find find) {
        String heading = Prospecting.heading(find.dx(), find.dz());
        if (heading == null) return word(viewer, "directions." + (find.dy() > 0 ? "above" : "below"));
        return word(viewer, "directions." + heading) + ", " + word(viewer, "directions." + Prospecting.vertical(find.dy()));
    }
    String oreName(String family) {
        return oreName(null, family);
    }
    String oreName(CommandSender viewer, String family) {
        String name = word(viewer, "ore-names." + family);
        return name.equals("ore-names." + family) ? family : name;
    }
    /** Text for a viewer: Core language files when available (see {@link VeinsTexts}), else config.yml. */
    String word(CommandSender viewer, String key) {
        if (texts != null) return texts.word(viewer, key);
        return getConfig().getString(VeinsTexts.legacyPath(key), key);
    }
    static Color oreColor(String family) {
        return switch (family) {
            case "coal" -> Color.fromRGB(45, 45, 45);
            case "iron" -> Color.fromRGB(216, 175, 147);
            case "copper" -> Color.fromRGB(226, 122, 80);
            case "gold", "nether_gold" -> Color.fromRGB(250, 215, 60);
            case "redstone" -> Color.fromRGB(225, 30, 30);
            case "lapis" -> Color.fromRGB(40, 80, 210);
            case "diamond" -> Color.fromRGB(90, 232, 222);
            case "emerald" -> Color.fromRGB(30, 205, 90);
            case "nether_quartz" -> Color.fromRGB(240, 235, 225);
            case "ancient_debris" -> Color.fromRGB(110, 72, 60);
            default -> Color.fromRGB(170, 110, 230);
        };
    }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent event) {
        previews.remove(event.getPlayer().getUniqueId());
    }
    @Override public void onDisable() {
        settings = null;
        if (regrowth != null) {
            try {
                regrowth.close();
            } catch (RuntimeException failure) {
                getLogger().severe("Could not save regrowth state: " + failure);
            }
            regrowth = null;
        }
        // Paper may iterate the populator list during shutdown: do not edit it while stopping.
        getServer().getWorlds().forEach(w -> w.getPopulators().remove(populator));
        previews.clear();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command != null && command.getName().equalsIgnoreCase("veinjournal")) {
            journal(sender);
            return true;
        }
        // Fallback without Vystorm Core 0.45.0; with it, VeinsCommands runs the commands through Core's tree.
        if (command != null && command.getName().equalsIgnoreCase("veinmap")) {
            veinMap(sender);
            return true;
        }
        if (command != null && command.getName().equalsIgnoreCase("veinmine")) {
            if (!sender.hasPermission("vystormveins.veinmine")) say(sender, "denied");
            else if (args.length != 0) say(sender, "veinmine-usage");
            else toggleVeinMining(sender);
            return true;
        }
        if (!sender.hasPermission("vystormveins.admin")) {
            say(sender, "denied");
            return true;
        }
        if (args.length > 1) {
            say(sender, "usage");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "reload" -> reloadCommand(sender);
            case "preview", "cancel", "apply" -> {
                if (!(sender instanceof Player player)) {
                    say(sender, "player-only");
                    break;
                }
                if (action.equals("preview")) preview(player);
                else if (action.equals("cancel")) cancel(player);
                else apply(player);
            }
            default -> say(sender, "usage");
        }
        return true;
    }
    /** {@code /veinmine}: a persistent personal choice, still subject to the server settings. */
    void toggleVeinMining(CommandSender sender) {
        if (!sender.hasPermission("vystormveins.veinmine")) say(sender, "denied");
        else if (!(sender instanceof Player player)) say(sender, "player-only");
        else {
            say(player, VeinMining.toggle(player) ? "veinmine-enabled" : "veinmine-disabled");
            if (!veinMining.enabled()) say(player, "veinmine-server-off");
        }
    }
    /** {@code /veinmap}: the ore map menu. */
    void veinMap(CommandSender sender) {
        if (!(sender instanceof Player player)) say(sender, "player-only");
        else if (!oreMap.enabled()) say(sender, "feature-off");
        else if (oreMapMenu == null) say(sender, "map-needs-core");
        else oreMapMenu.open(player);
    }
    private void guarded(CommandSender sender, Runnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            getLogger().warning("Command failed: " + failure);
            say(sender, "failed");
        }
    }
    void status(CommandSender sender) {
        guarded(sender, () -> {
            say(sender, "status", settings.worlds(), settings.rules().size(), generated.sum(), placed.sum());
            Regrowth tracker = regrowth;
            if (tracker == null || !tracker.enabled()) say(sender, "status-regrowth-off");
            else {
                int[] veins = tracker.counts();
                say(sender, "status-regrowth", veins[0], veins[1], veins[2]);
            }
            say(sender, prospecting.enabled() ? "status-prospecting" : "status-prospecting-off",
                    prospecting.item().getKey().getKey(), prospecting.radius());
            if (prospecting.enabled() && !prospecting.hidden().isEmpty())
                say(sender, "status-prospecting-hidden", String.join(", ", prospecting.hidden().stream()
                        .map(family -> oreName(sender, family)).sorted().toList()));
            if (veinMining.enabled()) say(sender, "status-veinmine", veinMining.maxBlocks(),
                    word(sender, veinMining.sneak() ? "status-sneak" : "status-no-sneak"));
            else say(sender, "status-veinmine-off");
            say(sender, miningSafety.enabled() ? "status-safety-on" : "status-safety-off");
            if (!oreMap.enabled()) say(sender, "status-map-off");
            else if (oreMapMenu == null) say(sender, "status-map-no-core");
            else say(sender, "status-map", oreMap.depth());
            if (claimsEnabled()) say(sender, "status-claims", claimMinutes(), claimMaxBlocks());
            else say(sender, "status-claims-off");
            DeepVeins.Options deep = deepVeins == null ? null : deepVeins.options();
            if (deepVeinsActive(deep, settings)) say(sender, "status-deep", deep.players(), deep.radius(), deep.maxY());
            else say(sender, "status-deep-off");
        });
    }
    void reloadCommand(CommandSender sender) {
        guarded(sender, () -> {
            Settings validated;
            Prospecting.Options nextProspecting;
            VeinMining.Options nextVeinMining;
            OreMap.Options nextOreMap;
            MiningSafety.Options nextSafety;
            Feature.Switches nextSwitches;
            try {
                YamlConfiguration next = readConfigFile(new java.io.File(getDataFolder(), "config.yml"), bundledConfig());
                validated = Settings.read(next);
                nextProspecting = Prospecting.Options.read(next);
                nextVeinMining = VeinMining.Options.read(next);
                nextOreMap = OreMap.Options.read(next);
                nextSafety = MiningSafety.Options.read(next);
                nextSwitches = Feature.Switches.read(next);
            } catch (Exception invalid) {
                getLogger().severe("config.yml not reloaded (the file was not changed), previous settings stay active: " + reason(invalid));
                say(sender, "reload-failed", reason(invalid));
                return;
            }
            settings = validated;
            prospecting = nextProspecting;
            veinMining = nextVeinMining;
            oreMap = nextOreMap;
            miningSafety = nextSafety;
            switches = nextSwitches;
            previews.clear();
            prospectCooldowns.clear();
            if (oreMapMenu != null) oreMapMenu.clearCooldowns();
            reloadConfig();
            configureRegrowth();
            if (deepVeins != null) deepVeins.apply();
            if (texts != null) texts.reload();
            applySwitches();
            say(sender, "reloaded");
        });
    }
    void preview(Player player) {
        if (off(player, Feature.CHUNK_PREVIEW)) return;
        guarded(player, () -> {
            World w = player.getWorld();
            int x = player.getLocation().getBlockX() >> 4, z = player.getLocation().getBlockZ() >> 4;
            if (!settings.worlds().contains(w.getName()) || !w.isChunkLoaded(x, z)) {
                say(player, "world-disabled");
                return;
            }
            Map<String, Integer> materials = new java.util.TreeMap<>();
            Generation.Result result = Generation.run(Generation.world(w), w.getSeed(), x, z, settings, true, cells -> {}, materials::putAll);
            previews.put(player.getUniqueId(), new Preview(w.getUID(), x, z, settings, System.nanoTime() + 30_000_000_000L));
            say(player, "preview", x, z, result.removed(), result.placed());
            materials.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(8).forEach(entry -> say(player, "preview-material", entry.getKey(), entry.getValue()));
            if (materials.size() > 8) say(player, "preview-material-more", materials.size() - 8);
        });
    }
    void cancel(Player player) {
        if (off(player, Feature.CHUNK_PREVIEW)) return;
        guarded(player, () -> {
            if (previews.remove(player.getUniqueId()) == null) {
                say(player, "no-preview");
                return;
            }
            say(player, "cancelled");
        });
    }
    void apply(Player player) {
        if (off(player, Feature.CHUNK_PREVIEW)) return;
        guarded(player, () -> {
            Preview p = previews.remove(player.getUniqueId());
            if (p != null && System.nanoTime() > p.expires) {
                say(player, "preview-expired");
                return;
            }
            if (p == null || System.nanoTime() > p.expires || p.settings != settings || !player.getWorld().getUID().equals(p.world)
                    || player.getLocation().getBlockX() >> 4 != p.x || player.getLocation().getBlockZ() >> 4 != p.z
                    || !player.getWorld().isChunkLoaded(p.x, p.z)) {
                        say(player, "preview-needed");
                        return;
                    }
            Generation.Result result = Generation.run(Generation.world(player.getWorld()), player.getWorld().getSeed(), p.x, p.z, p.settings, false,
                    cells -> {
                        if (regrowth != null) regrowth.generated(player.getWorld().getUID(), cells);
                    });
            say(player, "applied", result.removed(), result.placed());
        });
    }
    /**
     * config.yml as /vveins reload reads it, the same way the start reads {@link #getConfig()}: Bukkit gives that one
     * the jar's config.yml as defaults, so a config without a {@code deep-veins} section (from before 1.18.0) generates
     * deep veins with the jar's values. Only that section falls back here: a missing {@code deposits} section is still
     * rejected, and a missing {@code vein-mining} or {@code ore-map} section still means off (their switches are read
     * with an explicit default, which Bukkit takes before the defaults section, at start as on reload).
     */
    static YamlConfiguration readConfigFile(java.io.File file, YamlConfiguration bundled) throws Exception {
        YamlConfiguration next = new YamlConfiguration();
        next.load(file);
        YamlConfiguration defaults = new YamlConfiguration();
        org.bukkit.configuration.ConfigurationSection deep = bundled.getConfigurationSection("deep-veins");
        if (deep != null) for (String key : deep.getKeys(true))
            if (!deep.isConfigurationSection(key)) defaults.set("deep-veins." + key, deep.get(key));
        next.setDefaults(defaults);
        return next;
    }

    /** /vveins status says "on" only while the deep-vein generation rule really is in the active settings. */
    static boolean deepVeinsActive(DeepVeins.Options deep, Settings settings) {
        return deep != null && deep.enabled() && settings != null
                && settings.rules().stream().anyMatch(rule -> rule.id().equals(DeepVeins.DEPOSIT));
    }

    /** Short, single-line reason for a rejected reload, safe to show in chat. */
    static String reason(Throwable failure) {
        // a YAML error: line and column of the problem (SnakeYAML's first line names only what it was parsing)
        if (failure instanceof org.bukkit.configuration.InvalidConfigurationException) return YamlCheck.describe(failure);
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        String line = message.strip().lines().findFirst().orElse(message).strip();
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }
    void say(CommandSender sender, String key, Object... args) {
        if (texts != null) {
            texts.say(sender, key, args);
            return;
        }
        String text = getConfig().getString("messages." + key, key);
        for (int i = 0; i < args.length; i++) text = text.replace("{" + i + "}", String.valueOf(args[i]));
        sender.sendMessage("[VystormVeins] " + text);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command != null && command.getName().equalsIgnoreCase("veinjournal")) return List.of();
        if (command != null && command.getName().equalsIgnoreCase("veinmap")) return List.of();
        if (command != null && command.getName().equalsIgnoreCase("veinmine")) return List.of();
        if (!sender.hasPermission("vystormveins.admin") || args.length != 1) return List.of();
        return List.of("status", "reload", "preview", "apply", "cancel").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}

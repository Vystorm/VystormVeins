package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An old config.yml without a {@code deep-veins} section: the start (Bukkit's getConfig() with the jar config as
 * defaults) generates deep veins, so /vveins reload must too, and the status reports what really generates.
 * A missing {@code vein-mining} or {@code ore-map} section still means off after a reload.
 */
class DeepVeinsReloadTest {
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    /** The jar config; biome filters are left out because the biome registry needs a running server. */
    private static YamlConfiguration bundled() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        var deposits = config.getConfigurationSection("deposits");
        if (deposits != null) for (String id : deposits.getKeys(false)) deposits.set(id + ".biomes", null);
        return config;
    }

    /** The jar config without the given top-level sections, as an older config.yml would look. */
    private static Path oldConfig(Path folder, String... removed) throws Exception {
        YamlConfiguration old = bundled();
        for (String section : removed) old.set(section, null);
        Path file = folder.resolve("config.yml");
        old.save(file.toFile());
        return file;
    }

    /** What JavaPlugin.getConfig() hands the start: the file with the whole jar config as defaults. */
    private static YamlConfiguration startView(Path file) {
        YamlConfiguration start = YamlConfiguration.loadConfiguration(file.toFile());
        start.setDefaults(bundled());
        return start;
    }

    /** The deep-vein generation rule a config yields (materials taken as written: no server in unit tests). */
    private static java.util.List<at.esoren.vystormveins.engine.VeinRule> rules(YamlConfiguration config) {
        java.util.List<at.esoren.vystormveins.engine.VeinRule> rules = new java.util.ArrayList<>();
        DeepVeins.addRule(config, rules, s -> s);
        return rules;
    }

    private static boolean generatesDeepVeins(YamlConfiguration config) {
        return !rules(config).isEmpty();
    }

    @Test void givenOldConfigWithoutDeepVeins_whenReloaded_thenTheRuleStaysAsAtStart(@TempDir Path folder) throws Exception {
        Path file = oldConfig(folder, "deep-veins");
        assertTrue(generatesDeepVeins(startView(file)), "start generates deep veins");
        YamlConfiguration reloaded = VeinsPlugin.readConfigFile(file.toFile(), bundled());
        assertTrue(generatesDeepVeins(reloaded), "reload keeps the deep-vein rule");
    }

    @Test void givenOnlyTheAddedSwitch_whenReloaded_thenTheRuleStays(@TempDir Path folder) throws Exception {
        Path file = oldConfig(folder, "deep-veins");
        YamlConfiguration withSwitch = YamlConfiguration.loadConfiguration(file.toFile());
        withSwitch.set("deep-veins.enabled", true); // what Feature.addMissingKeys writes into an old config
        withSwitch.save(file.toFile());
        assertTrue(generatesDeepVeins(VeinsPlugin.readConfigFile(file.toFile(), bundled())));
    }

    @Test void givenDeepVeinsSwitchedOff_whenReloaded_thenNoRule(@TempDir Path folder) throws Exception {
        Path file = oldConfig(folder);
        YamlConfiguration off = YamlConfiguration.loadConfiguration(file.toFile());
        off.set("deep-veins.enabled", false);
        off.save(file.toFile());
        YamlConfiguration reloaded = VeinsPlugin.readConfigFile(file.toFile(), bundled());
        assertFalse(generatesDeepVeins(reloaded));
        assertFalse(reloaded.getBoolean("deep-veins.enabled", true));
    }

    @Test void givenOldConfigWithoutVeinMiningOrOreMap_whenReloaded_thenBothStayOff(@TempDir Path folder) throws Exception {
        Path file = oldConfig(folder, "vein-mining", "ore-map");
        YamlConfiguration reloaded = VeinsPlugin.readConfigFile(file.toFile(), bundled());
        assertFalse(VeinMining.Options.read(reloaded).enabled());
        assertFalse(OreMap.Options.read(reloaded).enabled());
        assertFalse(VeinMining.Options.read(startView(file)).enabled(), "the start agrees");
        assertFalse(OreMap.Options.read(startView(file)).enabled(), "the start agrees");
    }

    @Test void givenMissingDeposits_whenReloaded_thenStillRejected(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, "worlds: [world]\n");
        YamlConfiguration reloaded = VeinsPlugin.readConfigFile(file.toFile(), bundled());
        assertThrows(NullPointerException.class, () -> Settings.read(reloaded));
    }

    @Test void statusSaysOnOnlyWhileTheRuleGenerates() {
        DeepVeins.Options on = new DeepVeins.Options(true, Set.of(), -40, 3, 12, 15);
        Settings withRule = new Settings(Set.of("world"), Set.of(), rules(bundled()));
        Settings withoutRule = new Settings(Set.of("world"), Set.of(), java.util.List.of());
        assertTrue(VeinsPlugin.deepVeinsActive(on, withRule));
        assertFalse(VeinsPlugin.deepVeinsActive(on, withoutRule), "switch on but no rule: status must not say on");
        assertFalse(VeinsPlugin.deepVeinsActive(null, withRule));
    }
}

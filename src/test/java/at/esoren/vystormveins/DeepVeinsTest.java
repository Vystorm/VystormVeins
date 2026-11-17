package at.esoren.vystormveins;

import at.esoren.vystormveins.engine.VeinRule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeepVeinsTest {
    private static YamlConfiguration config(boolean enabled) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("deep-veins.enabled", enabled);
        yaml.set("deep-veins.vein.ore", "minecraft:ancient_debris");
        yaml.set("deep-veins.vein.hosts", List.of("minecraft:deepslate"));
        yaml.set("deep-veins.vein.size", 40);
        yaml.set("deep-veins.vein.chance", 0.05);
        yaml.set("deep-veins.players", 99);
        return yaml;
    }

    @Test
    void givenEnabled_whenRulesBuilt_thenDeepVeinAddedOnce() {
        List<VeinRule> rules = new ArrayList<>();
        DeepVeins.addRule(config(true), rules, s -> s);
        DeepVeins.addRule(config(true), rules, s -> s);
        assertEquals(1, rules.size());
        VeinRule rule = rules.getFirst();
        assertEquals(DeepVeins.DEPOSIT, rule.id());
        assertEquals(Set.of("minecraft:deepslate"), rule.hosts());
        assertEquals(40, rule.size());
        assertEquals(-48, rule.maxY());
    }

    @Test
    void givenDisabled_whenRulesBuilt_thenNothingAdded() {
        List<VeinRule> rules = new ArrayList<>();
        DeepVeins.addRule(config(false), rules, s -> s);
        assertTrue(rules.isEmpty());
    }

    @Test
    void givenOldDefaults_whenMigrated_thenLoweredOnlyIfUntouched() {
        YamlConfiguration old = new YamlConfiguration();
        old.set("deep-veins.vein.size", 48);
        old.set("deep-veins.vein.chance", 0.02);
        assertTrue(DeepVeins.migrateDefaults(old));
        assertEquals(24, old.getInt("deep-veins.vein.size"));
        assertEquals(0.01, old.getDouble("deep-veins.vein.chance"), 1e-9);
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("deep-veins.vein.size", 48);
        custom.set("deep-veins.vein.chance", 0.05);
        assertFalse(DeepVeins.migrateDefaults(custom));
    }

    @Test
    void givenMiners_whenCounted_thenMissingAndLimitsHold() {
        assertEquals(2, DeepVeins.missing(1, 3));
        assertEquals(0, DeepVeins.missing(4, 3));
        assertEquals(16, DeepVeins.Options.read(config(true)).players(), "clamped to 16");
    }
}

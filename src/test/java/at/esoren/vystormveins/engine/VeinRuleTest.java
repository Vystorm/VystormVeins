package at.esoren.vystormveins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VeinRuleTest {
    @Test
    void validatesNumericRangesAndKeys() {
        assertThrows(IllegalArgumentException.class, () -> rule(6, 5, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> rule(0, 5, 65, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> rule(0, 5, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> rule(0, 5, 1, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new VeinRule("ore", "iron_ore", null,
                Set.of("minecraft:stone"), Set.of(), -10, 10, 1, 1, 1));
    }

    @Test
    void storesDefensiveImmutableCopies() {
        Set<String> hosts = new HashSet<>(Set.of("minecraft:stone"));
        VeinRule rule = new VeinRule("ore", "minecraft:iron_ore", null, hosts,
                Set.of("minecraft:plains"), -10, 10, 1, 1, 1);
        hosts.add("minecraft:dirt");
        assertEquals(Set.of("minecraft:stone"), rule.hosts());
        assertThrows(UnsupportedOperationException.class, () -> rule.biomes().add("minecraft:forest"));
    }

    private static VeinRule rule(int minY, int maxY, int attempts, int size, double chance) {
        return new VeinRule("ore", "minecraft:iron_ore", null, Set.of("minecraft:stone"), Set.of(),
                minY, maxY, attempts, size, chance);
    }
}

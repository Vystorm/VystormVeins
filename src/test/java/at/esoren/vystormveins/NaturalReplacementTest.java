package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.esoren.vystormveins.engine.BlockVolume;
import at.esoren.vystormveins.engine.VeinRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class NaturalReplacementTest {
    private static final int CHUNK_X = -2;
    private static final int CHUNK_Z = 3;

    @Test
    void removesOnlySelectedNaturalOreFamilyIncludingItsDeepslateVariant() {
        Fixture volume = fixtureWithNaturalOres();
        Settings settings = settings(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));

        Generation.Result result = Generation.run(volume, 917L, CHUNK_X, CHUNK_Z, settings, false);

        assertEquals(2, result.removed());
        assertEquals("minecraft:stone", volume.material(x(2), -1, z(2)));
        assertEquals("minecraft:deepslate", volume.material(x(3), 3, z(3)));
        assertEquals("minecraft:coal_ore", volume.material(x(4), 0, z(4)));
        assertEquals("minecraft:deepslate_gold_ore", volume.material(x(5), 4, z(5)));
    }

    @Test
    void previewCountsMatchApplicationAndLeavesPreviewVolumeUntouched() {
        Fixture preview = fixtureWithNaturalOres();
        Fixture applied = fixtureWithNaturalOres();
        Map<Position, String> initial = Map.copyOf(preview.blocks);
        Settings settings = settings(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));

        Generation.Result predicted = Generation.run(preview, 917L, CHUNK_X, CHUNK_Z, settings, true);
        Generation.Result changed = Generation.run(applied, 917L, CHUNK_X, CHUNK_Z, settings, false);

        assertEquals(initial, preview.blocks);
        assertEquals(predicted, changed);
        assertNotEquals(initial, applied.blocks);
    }

    @Test
    void togglingNaturalReplacementDoesNotMoveGeneratedVeins() {
        Fixture unchanged = fixtureWithNaturalOres();
        Fixture replaced = fixtureWithNaturalOres();

        Generation.run(unchanged, 217L, CHUNK_X, CHUNK_Z, settings(Set.of()), false);
        Generation.run(replaced, 217L, CHUNK_X, CHUNK_Z, settings(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")), false);

        assertEquals(orePositions(unchanged, "minecraft:copper_ore"),
                orePositions(replaced, "minecraft:copper_ore"));
        assertTrue(orePositions(unchanged, "minecraft:copper_ore").size() > 0);
    }

    @Test
    void parserAcceptsOnlyKnownFamiliesWithBooleanValues() throws Exception {
        YamlConfiguration config = yaml("replace_natural:\n  iron: true\n  coal: false\n  ancient_debris: true\n");

        assertEquals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:ancient_debris"),
                NaturalReplacement.read(config));
        assertThrows(IllegalArgumentException.class,
                () -> NaturalReplacement.read(yaml("replace_natural:\n  unobtainium: true\n")));
        assertThrows(IllegalArgumentException.class,
                () -> NaturalReplacement.read(yaml("replace_natural:\n  iron: 'true'\n")));
    }

    @Test
    void explicitReplacementMapOverridesLegacyAndDefaultsDoNotChangeLegacy() throws Exception {
        YamlConfiguration legacy=yaml("replace-vanilla: true\n");
        legacy.setDefaults(yaml("replace_natural:\n  iron: false\n"));
        assertTrue(NaturalReplacement.read(legacy).contains("minecraft:diamond_ore"));
        assertEquals(Set.of(),NaturalReplacement.read(yaml("replace-vanilla: true\nreplace_natural: {}\n")));
        assertThrows(IllegalArgumentException.class,()->NaturalReplacement.read(yaml("replace_natural: true\n")));
    }

    private static Settings settings(Set<String> replacement) {
        VeinRule copper = new VeinRule("test-copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
                Set.of("minecraft:stone", "minecraft:deepslate"), Set.of(), -4, 7, 8, 8, 1.0);
        return new Settings(Set.of(), replacement, List.of(copper));
    }

    private static Fixture fixtureWithNaturalOres() {
        Fixture volume = new Fixture(-8, 16);
        volume.blocks.put(new Position(x(2), -1, z(2)), "minecraft:iron_ore");
        volume.blocks.put(new Position(x(3), 3, z(3)), "minecraft:deepslate_iron_ore");
        volume.blocks.put(new Position(x(4), 0, z(4)), "minecraft:coal_ore");
        volume.blocks.put(new Position(x(5), 4, z(5)), "minecraft:deepslate_gold_ore");
        return volume;
    }

    private static Set<Position> orePositions(Fixture fixture, String material) {
        Set<Position> positions = new HashSet<>();
        fixture.blocks.forEach((position, block) -> {
            if (block.equals(material)) positions.add(position);
        });
        return positions;
    }

    private static int x(int localX) { return CHUNK_X * 16 + localX; }
    private static int z(int localZ) { return CHUNK_Z * 16 + localZ; }

    private static YamlConfiguration yaml(String value) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(value);
        return config;
    }

    private static final class Fixture implements BlockVolume {
        private final int minY;
        private final int maxY;
        private final Map<Position, String> blocks = new HashMap<>();

        private Fixture(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override public int minY() { return minY; }
        @Override public int maxY() { return maxY; }

        @Override
        public String material(int x, int y, int z) {
            check(x, y, z);
            return blocks.getOrDefault(new Position(x, y, z), y < 0 ? "minecraft:deepslate" : "minecraft:stone");
        }

        @Override public String biome(int x, int y, int z) { check(x, y, z); return "minecraft:plains"; }

        @Override
        public void place(int x, int y, int z, String material) {
            check(x, y, z);
            blocks.put(new Position(x, y, z), material);
        }

        private void check(int x, int y, int z) {
            if (y < minY || y >= maxY) throw new AssertionError("generation accessed outside fixture height");
            if ((x >> 4) != CHUNK_X || (z >> 4) != CHUNK_Z) {
                throw new AssertionError("invalid requested chunk");
            }
        }
    }

    private record Position(int x, int y, int z) { }
}

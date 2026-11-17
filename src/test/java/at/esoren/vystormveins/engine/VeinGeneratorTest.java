package at.esoren.vystormveins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VeinGeneratorTest {
    private static final String STONE = "minecraft:stone";
    private static final String PLAINS = "minecraft:plains";

    @Test
    void generatesRepeatableResultsAndSeparatesRuleStreams() {
        VeinRule iron = rule("iron", "minecraft:iron_ore", Set.of(STONE), Set.of(), -20, 30, 16, 12, 1);
        VeinRule gold = rule("gold", "minecraft:gold_ore", Set.of(STONE), Set.of(), -20, 30, 16, 12, 1);
        Fixture first = new Fixture(-64, 320);
        Fixture repeated = new Fixture(-64, 320);
        Fixture withAnotherRule = new Fixture(-64, 320);
        Fixture differentSeed = new Fixture(-64, 320);

        VeinGenerator.generate(first, 991L, -3, 5, List.of(iron), false);
        VeinGenerator.generate(repeated, 991L, -3, 5, List.of(iron), false);
        VeinGenerator.generate(withAnotherRule, 991L, -3, 5, List.of(iron, gold), false);
        VeinGenerator.generate(differentSeed, 992L, -3, 5, List.of(iron), false);

        assertEquals(first.blocks, repeated.blocks);
        assertEquals(oreCells(first, "minecraft:iron_ore"), oreCells(withAnotherRule, "minecraft:iron_ore"));
        assertNotEquals(first.blocks, differentSeed.blocks);
    }

    @Test
    void onlyTouchesConfiguredHostBiomeChunkAndHeightAndUsesDeepMaterialBelowZero() {
        VeinRule rule = rule("deep", "minecraft:iron_ore", Set.of(STONE), Set.of(PLAINS),
                -12, 6, 64, 256, 1);
        Fixture volume = new Fixture(-64, 320);
        volume.restrictToChunk(-2, -1);
        volume.biomeFilter = (x, y, z) -> y < 0;
        volume.hostFilter = (x, y, z) -> y < 0;

        int changed = VeinGenerator.generate(volume, 4L, -2, -1, List.of(rule), false);

        assertEquals(changed, volume.blocks.size());
        assertTrue(volume.blocks.keySet().stream().allMatch(cell -> cell.x >= -32 && cell.x < -16
                && cell.z >= -16 && cell.z < 0 && cell.y >= -12 && cell.y <= 6));
        assertTrue(volume.blocks.values().stream().allMatch("minecraft:deepslate_iron_ore"::equals));
        assertTrue(volume.outOfBoundsReads == 0);
    }

    @Test
    void previewDoesNotMutateAndMatchesApplicationCount() {
        VeinRule rule = rule("copper", "minecraft:copper_ore", Set.of(STONE), Set.of(), -64, 63, 10, 9, 1);
        Fixture volume = new Fixture(-64, 320);
        volume.restrictToChunk(2, -4);

        int previewCount = VeinGenerator.generate(volume, 721L, 2, -4, List.of(rule), true);
        assertTrue(volume.blocks.isEmpty());
        int appliedCount = VeinGenerator.generate(volume, 721L, 2, -4, List.of(rule), false);

        assertEquals(previewCount, appliedCount);
        assertEquals(appliedCount, volume.blocks.size());
        assertEquals(0, volume.outOfBoundsReads);
    }

    @Test
    void zeroChanceWritesNothing() {
        VeinRule rule = rule("none", "minecraft:coal_ore", Set.of(STONE), Set.of(), -64, 320, 64, 256, 0);
        Fixture volume = new Fixture(-64, 320);
        assertEquals(0, VeinGenerator.generate(volume, 3L, 0, 0, List.of(rule), false));
        assertTrue(volume.blocks.isEmpty());
    }

    private static Set<String> oreCells(Fixture fixture, String ore) {
        return fixture.blocks.entrySet().stream().filter(entry -> entry.getValue().equals(ore))
                .map(entry -> entry.getKey().toString()).collect(java.util.stream.Collectors.toSet());
    }

    private static VeinRule rule(String id, String ore, Set<String> hosts, Set<String> biomes,
            int minY, int maxY, int attempts, int size, double chance) {
        return new VeinRule(id, ore, "minecraft:deepslate_" + ore.substring("minecraft:".length()),
                hosts, biomes, minY, maxY, attempts, size, chance);
    }

    private static final class Fixture implements BlockVolume {
        private final int minY;
        private final int maxY;
        private final Map<Cell, String> blocks = new HashMap<>();
        private int outOfBoundsReads;
        private Integer chunkX;
        private Integer chunkZ;
        private CellFilter hostFilter = (x, y, z) -> true;
        private CellFilter biomeFilter = (x, y, z) -> true;

        private Fixture(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }

        private void restrictToChunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override public int minY() { return minY; }
        @Override public int maxY() { return maxY; }

        @Override
        public String material(int x, int y, int z) {
            checkBounds(x, y, z);
            return hostFilter.test(x, y, z) ? STONE : "minecraft:dirt";
        }

        @Override
        public String biome(int x, int y, int z) {
            checkBounds(x, y, z);
            return biomeFilter.test(x, y, z) ? PLAINS : "minecraft:desert";
        }

        @Override
        public void place(int x, int y, int z, String material) {
            checkBounds(x, y, z);
            blocks.put(new Cell(x, y, z), material);
        }

        private void checkBounds(int x, int y, int z) {
            boolean outsideChunk = chunkX != null && (x < chunkX * 16 || x >= chunkX * 16 + 16
                    || z < chunkZ * 16 || z >= chunkZ * 16 + 16);
            if (y < minY || y >= maxY || outsideChunk) {
                outOfBoundsReads++;
                throw new AssertionError("access outside fixture bounds");
            }
        }
    }

    private record Cell(int x, int y, int z) { }
    @FunctionalInterface private interface CellFilter { boolean test(int x, int y, int z); }
}

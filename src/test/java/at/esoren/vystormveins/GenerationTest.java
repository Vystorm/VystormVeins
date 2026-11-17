package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.esoren.vystormveins.engine.BlockVolume;
import at.esoren.vystormveins.engine.VeinRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GenerationTest {
    @Test void previewMaterialTotalsMatchActualPlanWithoutWritingOrTracking() {
        Fixture preview=withVanillaOres(), applied=withVanillaOres();
        Map<Position,String> before=Map.copyOf(preview.blocks);
        Settings settings=new Settings(Set.of(),true,List.of(new VeinRule("iron","minecraft:iron_ore","minecraft:deepslate_iron_ore",
                Set.of("minecraft:stone","minecraft:deepslate"),Set.of(),-4,7,8,12,1)));
        Map<String,Integer> counts=new HashMap<>();
        var result=Generation.run(preview,551L,CHUNK_X,CHUNK_Z,settings,true,cells->{throw new AssertionError("Preview cannot register regrowth");},counts::putAll);
        assertEquals(before,preview.blocks);
        assertEquals(result.placed(),counts.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(counts.containsKey("minecraft:iron_ore"));
        assertTrue(counts.containsKey("minecraft:deepslate_iron_ore"));
        Map<String,Integer> actual=new HashMap<>();
        Generation.run(applied,551L,CHUNK_X,CHUNK_Z,settings,false,cells->cells.forEach(cell->actual.merge(cell.material(),1,Integer::sum)));
        assertEquals(actual,counts);
        assertEquals(0,preview.outsideAccesses);
    }
    @Test void trackingReceivesOnlyActualGeneratedCellsAndNeverPreviews() {
        Fixture volume = withVanillaOres();
        Settings settings = new Settings(Set.of(), false, List.of(new VeinRule(
                "tracked", "minecraft:iron_ore", null, Set.of("minecraft:stone"),Set.of(),0,7,4,12,1)));
        java.util.List<Generation.OreCell> tracked = new java.util.ArrayList<>();
        Generation.run(volume,77L,CHUNK_X,CHUNK_Z,settings,true,tracked::addAll);
        assertTrue(tracked.isEmpty());
        var result=Generation.run(volume,77L,CHUNK_X,CHUNK_Z,settings,false,tracked::addAll);
        assertTrue(result.placed()>0); assertEquals(result.placed(),tracked.size());
        for(var cell:tracked) {
            assertEquals(cell.material(),volume.material(cell.x(),cell.y(),cell.z()));
            assertEquals("tracked",cell.deposit());
        }
    }
    private static final int CHUNK_X = -2;
    private static final int CHUNK_Z = 3;

    @Test
    void givenVanillaReplacementWhenPreviewingThenCountsMatchApplicationWithoutMutatingPreviewFixture() {
        Fixture preview = withVanillaOres();
        Fixture applied = withVanillaOres();
        Map<Position, String> startingBlocks = Map.copyOf(preview.blocks);
        Settings settings = new Settings(Set.of(), true, List.of(new VeinRule(
                "test-iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                Set.of("minecraft:stone", "minecraft:deepslate"), Set.of(),
                -4, 7, 8, 6, 1.0)));

        Generation.Result predicted = Generation.run(preview, 551L, CHUNK_X, CHUNK_Z, settings, true);
        Generation.Result changed = Generation.run(applied, 551L, CHUNK_X, CHUNK_Z, settings, false);

        assertEquals(startingBlocks, preview.blocks, "preview leaves the original volume untouched");
        assertEquals(predicted.removed(), changed.removed());
        assertEquals(predicted.placed(), changed.placed());
        assertTrue(predicted.removed() >= 2);
        assertTrue(predicted.placed() > 0);
        assertNotEquals(startingBlocks, applied.blocks);
        assertEquals(0, preview.outsideAccesses);
        assertEquals(0, applied.outsideAccesses);
    }

    @Test
    void givenReplacementDisabledWhenGeneratingThenExistingVanillaOresArePreserved() {
        Fixture volume = withVanillaOres();
        Map<Position, String> startingBlocks = Map.copyOf(volume.blocks);
        Settings settings = new Settings(Set.of(), false, List.of(new VeinRule(
                "disabled", "minecraft:diamond_ore", null, Set.of("minecraft:stone"), Set.of(),
                -4, 7, 0, 1, 1.0)));

        Generation.Result result = Generation.run(volume, 9L, CHUNK_X, CHUNK_Z, settings, false);

        assertEquals(0, result.removed());
        assertEquals(0, result.placed());
        assertEquals(startingBlocks, volume.blocks);
    }

    @Test
    void givenGenerationRequestWhenRunningThenNeverReadsOrWritesOutsideRequestedChunk() {
        Fixture volume = withVanillaOres();
        Settings settings = new Settings(Set.of(), true, List.of(new VeinRule(
                "bounded", "minecraft:coal_ore", null,
                Set.of("minecraft:stone", "minecraft:deepslate"), Set.of(),
                -4, 7, 8, 12, 1.0)));

        Generation.run(volume, 77L, CHUNK_X, CHUNK_Z, settings, false);

        assertEquals(0, volume.outsideAccesses);
        assertTrue(volume.blocks.keySet().stream().allMatch(position -> volume.insideRequestedChunk(position)));
    }

    private static Fixture withVanillaOres() {
        Fixture fixture = new Fixture(-8, 16, CHUNK_X, CHUNK_Z);
        fixture.blocks.put(new Position(CHUNK_X * 16 + 2, -1, CHUNK_Z * 16 + 4), "minecraft:coal_ore");
        fixture.blocks.put(new Position(CHUNK_X * 16 + 9, 3, CHUNK_Z * 16 + 11), "minecraft:deepslate_iron_ore");
        return fixture;
    }

    private static final class Fixture implements BlockVolume {
        private final int minY;
        private final int maxY;
        private final int chunkX;
        private final int chunkZ;
        private final Map<Position, String> blocks = new HashMap<>();
        private int outsideAccesses;

        private Fixture(int minY, int maxY, int chunkX, int chunkZ) {
            this.minY = minY;
            this.maxY = maxY;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override public int minY() { return minY; }
        @Override public int maxY() { return maxY; }

        @Override
        public String material(int x, int y, int z) {
            checkBounds(x, y, z);
            return blocks.getOrDefault(new Position(x, y, z), y < 0 ? "minecraft:deepslate" : "minecraft:stone");
        }

        @Override
        public String biome(int x, int y, int z) {
            checkBounds(x, y, z);
            return "minecraft:plains";
        }

        @Override
        public void place(int x, int y, int z, String material) {
            checkBounds(x, y, z);
            blocks.put(new Position(x, y, z), material);
        }

        private void checkBounds(int x, int y, int z) {
            if (y < minY || y >= maxY || !insideRequestedChunk(new Position(x, y, z))) {
                outsideAccesses++;
                throw new AssertionError("generation accessed outside fixture bounds");
            }
        }

        private boolean insideRequestedChunk(Position position) {
            return position.x >= chunkX * 16 && position.x < chunkX * 16 + 16
                    && position.z >= chunkZ * 16 && position.z < chunkZ * 16 + 16;
        }
    }

    private record Position(int x, int y, int z) { }
}

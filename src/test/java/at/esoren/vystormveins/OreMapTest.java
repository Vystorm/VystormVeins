package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class OreMapTest {
    @Test void gridHasThePlayerInTheMiddleAndNorthOnTop() {
        assertArrayEquals(new int[]{0,0},OreMap.offset(OreMap.CENTRE));
        assertArrayEquals(new int[]{-4,-2},OreMap.offset(0));
        assertArrayEquals(new int[]{4,2},OreMap.offset(44));
        assertArrayEquals(new int[]{1,0},OreMap.offset(OreMap.CENTRE+1));
        assertArrayEquals(new int[]{0,-1},OreMap.offset(OreMap.CENTRE-OreMap.COLUMNS));
    }

    @Test void tallyCountsOnlyTheHeightBandMostFrequentFirstAndRespectsHiddenOres() {
        Prospecting.Lookup chunk=(x,y,z) -> {
            if(y==10 && x<3 && z==0) return "minecraft:deepslate_iron_ore";
            if(y==12 && z==5) return "minecraft:coal_ore";
            if(y==11 && x==0 && z==0) return "minecraft:diamond_ore";
            if(y==40) return "minecraft:gold_ore";
            return "minecraft:stone";
        };
        var counts=OreMap.tally(chunk,8,12,-64,320,Set.of(),Set.of("diamond"));
        assertEquals(List.of("coal","iron"),List.copyOf(counts.keySet()));
        assertEquals(16,counts.get("coal"));
        assertEquals(3,counts.get("iron"));
        assertTrue(OreMap.tally(chunk,8,12,-64,12,Set.of(),Set.of()).containsKey("diamond"));
        assertFalse(OreMap.tally(chunk,8,12,-64,12,Set.of(),Set.of()).containsKey("coal"));
    }

    @Test void tileShowsTheFilteredOrTheRichestFamily() {
        Map<String,Integer> ores=new LinkedHashMap<>(); ores.put("coal",9); ores.put("iron",2);
        var tile=new OreMap.Tile(3,-1,true,ores);
        assertEquals("coal",tile.shown(null));
        assertEquals("iron",tile.shown("iron"));
        assertNull(tile.shown("diamond"));
        assertEquals(11,tile.count(null));
        assertEquals(2,tile.count("iron"));
        assertNull(new OreMap.Tile(0,0,false,Map.of()).shown(null));
    }

    @Test void filterCyclesThroughAllFamiliesBothWays() {
        var tiles=List.of(new OreMap.Tile(0,0,true,Map.of("iron",1)),new OreMap.Tile(1,0,true,Map.of("coal",1,"iron",4)));
        var families=OreMap.families(tiles);
        assertEquals(List.of("coal","iron"),families);
        assertEquals("coal",OreMap.cycle(families,null,1));
        assertEquals("iron",OreMap.cycle(families,"coal",1));
        assertNull(OreMap.cycle(families,"iron",1));
        assertEquals("iron",OreMap.cycle(families,null,-1));
        assertEquals("coal",OreMap.cycle(families,"gone",1));
    }

    @Test void stackSizeAndIcons() {
        assertEquals(1,OreMap.amount(0));
        assertEquals(17,OreMap.amount(17));
        assertEquals(64,OreMap.amount(500));
        assertEquals(Material.RAW_IRON,OreMap.icon("iron"));
        assertEquals(Material.ANCIENT_DEBRIS,OreMap.icon("ancient_debris"));
    }

    @Test void missingSectionKeepsTheMapOffAndValuesAreClamped() {
        assertFalse(OreMap.Options.read(new YamlConfiguration()).enabled());
        YamlConfiguration config=new YamlConfiguration();
        config.set("ore-map.enabled",true);
        config.set("ore-map.depth",100);
        config.set("ore-map.cooldown-seconds",-5);
        var options=OreMap.Options.read(config);
        assertTrue(options.enabled());
        assertEquals(32,options.depth());
        assertEquals(0,options.cooldownMillis());
    }

    @Test void newInstallsGetBothFeaturesFromTheBundledConfig() {
        var config=YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml")),StandardCharsets.UTF_8));
        assertTrue(OreMap.Options.read(config).enabled());
        assertEquals(8,OreMap.Options.read(config).depth());
        var mining=VeinMining.Options.read(config);
        assertTrue(mining.enabled());
        assertEquals(32,mining.maxBlocks());
        assertTrue(mining.excluded().isEmpty());
    }
}

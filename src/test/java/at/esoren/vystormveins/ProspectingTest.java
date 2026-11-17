package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ProspectingTest {
    private static Prospecting.Lookup world(Map<String,String> blocks) {
        return (x,y,z) -> blocks.getOrDefault(x+","+y+","+z,"minecraft:stone");
    }

    @Test void deepslateAndNormalOresShareOneFamily() {
        assertEquals("iron",Prospecting.family("minecraft:iron_ore",Set.of()));
        assertEquals("iron",Prospecting.family("minecraft:deepslate_iron_ore",Set.of()));
        assertEquals("nether_quartz",Prospecting.family("minecraft:nether_quartz_ore",Set.of()));
        assertEquals("ancient_debris",Prospecting.family("minecraft:ancient_debris",Set.of()));
        assertNull(Prospecting.family("minecraft:stone",Set.of()));
        assertNull(Prospecting.family(null,Set.of()));
        assertEquals("minecraft:raw_iron_block",Prospecting.family("minecraft:raw_iron_block",Set.of("minecraft:raw_iron_block")));
    }

    @Test void scanReportsNearestBlockAndCountPerFamilySortedByDistance() {
        Map<String,String> blocks=new HashMap<>();
        blocks.put("3,60,0","minecraft:iron_ore");
        blocks.put("4,60,0","minecraft:deepslate_iron_ore");
        blocks.put("0,58,1","minecraft:diamond_ore");
        blocks.put("20,60,0","minecraft:gold_ore");
        var finds=Prospecting.scan(world(blocks),0,60,0,8,-64,320,Set.of());
        assertEquals(2,finds.size());
        assertEquals(new Prospecting.Find("diamond",0,-2,1,1),finds.get(0));
        assertEquals(new Prospecting.Find("iron",3,0,0,2),finds.get(1));
    }

    @Test void scanSkipsUnloadedPositionsAndWorldBounds() {
        Prospecting.Lookup lookup=(x,y,z) -> x<0?null:"minecraft:coal_ore";
        var finds=Prospecting.scan(lookup,0,0,0,2,0,1,Set.of());
        assertEquals(1,finds.size());
        // Only y=0 is inside the world and only x>=0 is loaded: 3 x 1 x 5 positions.
        assertEquals(15,finds.getFirst().count());
    }

    @Test void headingsFollowMinecraftCompass() {
        assertEquals("N",Prospecting.heading(0,-5));
        assertEquals("S",Prospecting.heading(0,5));
        assertEquals("E",Prospecting.heading(5,0));
        assertEquals("W",Prospecting.heading(-5,0));
        assertEquals("NE",Prospecting.heading(4,-4));
        assertEquals("SW",Prospecting.heading(-3,3));
        assertNull(Prospecting.heading(0,0));
        assertEquals("up",Prospecting.vertical(3));
        assertEquals("level",Prospecting.vertical(-2));
        assertEquals("down",Prospecting.vertical(-3));
    }

    @Test void optionsUseDefaultsAndClampRanges() {
        YamlConfiguration config=new YamlConfiguration();
        assertEquals(Prospecting.Options.DEFAULTS,Prospecting.Options.read(config));
        config.set("prospecting.radius",99);
        config.set("prospecting.cooldown-seconds",-4);
        config.set("prospecting.item","compass");
        var options=Prospecting.Options.read(config);
        assertEquals(16,options.radius());
        assertEquals(0,options.cooldownMillis());
        assertEquals(Material.COMPASS,options.item());
    }

    @Test void hiddenOresAcceptFamiliesAndBlocks() {
        YamlConfiguration config=new YamlConfiguration();
        config.set("prospecting.hidden-ores",List.of("Diamond"," ancient_debris","minecraft:deepslate_emerald_ore","raw_iron_block"));
        assertEquals(Set.of("diamond","ancient_debris","emerald","minecraft:raw_iron_block"),Prospecting.Options.read(config).hidden());
        config.set("prospecting.hidden-ores",List.of("unobtainium"));
        var error=assertThrows(IllegalArgumentException.class,()->Prospecting.Options.read(config));
        assertTrue(error.getMessage().contains("unobtainium"));
    }

    @Test void hiddenFamiliesAreDroppedFromTheResult() {
        Map<String,String> blocks=new HashMap<>();
        blocks.put("1,60,0","minecraft:diamond_ore");
        blocks.put("3,60,0","minecraft:iron_ore");
        blocks.put("5,60,0","minecraft:raw_iron_block");
        var finds=Prospecting.scan(world(blocks),0,60,0,8,-64,320,Set.of("minecraft:raw_iron_block"));
        assertEquals(3,finds.size());
        var visible=Prospecting.visible(finds,Set.of("diamond","minecraft:raw_iron_block"));
        assertEquals(List.of(new Prospecting.Find("iron",3,0,0,1)),visible);
        assertSame(finds,Prospecting.visible(finds,Set.of()));
    }

    @Test void trailKeepsPointingAtTheOreAfterThePlayerMoves() {
        // Ore found at 10/60/0 while standing at 0/60/0; the player then walks to 10/60/-5.
        double[] before=Prospecting.towards(0.5,61.62,0.5,10,60,0);
        double[] after=Prospecting.towards(10.5,61.62,-4.5,10,60,0);
        assertTrue(before[0]>0.9);
        assertEquals(0,after[0],1e-9);
        assertTrue(after[2]>0.9,"after walking past, the trail must turn south to the same ore");
        assertNull(Prospecting.towards(10.5,60.5,0.5,10,60,0));
    }

    @Test void unknownItemIsRejectedForReloadValidation() {
        YamlConfiguration config=new YamlConfiguration();
        config.set("prospecting.item","not_an_item");
        var error=assertThrows(IllegalArgumentException.class,()->Prospecting.Options.read(config));
        assertTrue(error.getMessage().contains("not_an_item"));
    }

    @Test void directionTextCombinesHeadingAndHeight() {
        VeinsPlugin plugin=mock(VeinsPlugin.class,CALLS_REAL_METHODS);
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.directions.NE","nordöstlich");
        config.set("messages.directions.down","weiter unten");
        config.set("messages.directions.below","direkt unter dir");
        config.set("ore-names.iron","Eisen");
        doReturn(config).when(plugin).getConfig();
        assertEquals("nordöstlich, weiter unten",plugin.direction(new Prospecting.Find("iron",3,-6,-3,1)));
        assertEquals("direkt unter dir",plugin.direction(new Prospecting.Find("iron",0,-6,0,1)));
        assertEquals("Eisen",plugin.oreName("iron"));
        assertEquals("emerald",plugin.oreName("emerald"));
    }
}

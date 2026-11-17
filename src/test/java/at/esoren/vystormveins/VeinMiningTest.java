package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class VeinMiningTest {
    private static Prospecting.Lookup world(Map<String,String> blocks) {
        return (x,y,z) -> blocks.getOrDefault(x+","+y+","+z,"minecraft:stone");
    }
    private static Set<String> keys(List<int[]> found) {
        Set<String> result=new LinkedHashSet<>();
        for(int[] p:found) result.add(p[0]+","+p[1]+","+p[2]);
        return result;
    }

    @Test void collectsTheConnectedVeinIncludingDiagonalsAndDeepslateButNotTheOrigin() {
        Map<String,String> blocks=new HashMap<>();
        blocks.put("0,10,0","minecraft:iron_ore");
        blocks.put("1,10,0","minecraft:iron_ore");
        blocks.put("2,11,1","minecraft:deepslate_iron_ore"); // diagonal to 1,10,0
        blocks.put("0,10,1","minecraft:coal_ore");            // other family
        blocks.put("5,10,0","minecraft:iron_ore");            // not connected
        var found=keys(VeinMining.collect(world(blocks),0,10,0,"iron",Set.of(),32,-64,320));
        assertEquals(Set.of("1,10,0","2,11,1"),found);
    }

    @Test void nearestBlocksComeFirstAndTheLimitIsKept() {
        Map<String,String> blocks=new HashMap<>();
        for(int x=-3;x<=3;x++) blocks.put(x+",0,0","minecraft:coal_ore");
        var found=VeinMining.collect(world(blocks),0,0,0,"coal",Set.of(),2,-64,320);
        assertEquals(2,found.size());
        for(int[] p:found) assertEquals(1,Math.abs(p[0]));
    }

    @Test void neverLeavesTheReachOrTheWorldAndSkipsUnloadedBlocks() {
        Prospecting.Lookup allOre=(x,y,z) -> x<-2?null:"minecraft:gold_ore";
        var found=VeinMining.collect(allOre,0,0,0,"gold",Set.of(),10_000,0,3);
        for(int[] p:found) {
            assertTrue(p[0]>=-2 && Math.abs(p[0])<=VeinMining.REACH && Math.abs(p[2])<=VeinMining.REACH);
            assertTrue(p[1]>=0 && p[1]<3);
        }
        assertEquals(19*33*3-1,found.size());
    }

    @Test void customDepositOresAndUnknownFamilies() {
        Map<String,String> blocks=Map.of("0,0,0","minecraft:raw_iron_block","1,0,0","minecraft:raw_iron_block");
        assertEquals(1,VeinMining.collect(world(blocks),0,0,0,"minecraft:raw_iron_block",Set.of("minecraft:raw_iron_block"),8,-64,320).size());
        assertTrue(VeinMining.collect(world(blocks),0,0,0,"minecraft:raw_iron_block",Set.of(),8,-64,320).isEmpty());
        assertTrue(VeinMining.collect(world(blocks),0,0,0,null,Set.of(),8,-64,320).isEmpty());
    }

    @Test void toolProtectionStopsWithOneUseLeft() {
        assertFalse(VeinMining.toolAtLimit(0,1561));
        assertFalse(VeinMining.toolAtLimit(1559,1561));
        assertTrue(VeinMining.toolAtLimit(1560,1561));
        assertFalse(VeinMining.toolAtLimit(0,0));
    }

    @Test void missingSectionKeepsVeinMiningOffAndValuesAreClamped() {
        assertFalse(VeinMining.Options.read(new YamlConfiguration()).enabled());
        YamlConfiguration config=new YamlConfiguration();
        config.set("vein-mining.enabled",true);
        config.set("vein-mining.max-blocks",9999);
        config.set("vein-mining.excluded-ores",List.of("Ancient_Debris","diamond"));
        var options=VeinMining.Options.read(config);
        assertTrue(options.enabled());
        assertEquals(128,options.maxBlocks());
        assertEquals(Set.of("ancient_debris","diamond"),options.excluded());
        assertTrue(options.sneak() && options.respectRegrowth() && options.protectTool());
    }

    @Test void dropsAreCollectedAtTheFirstBlockUnlessSwitchedOff() {
        assertTrue(VeinMining.Options.DEFAULTS.collectDrops());
        YamlConfiguration config=new YamlConfiguration();
        config.set("vein-mining.enabled",true);
        assertTrue(VeinMining.Options.read(config).collectDrops());
        config.set("vein-mining.collect-drops",false);
        assertFalse(VeinMining.Options.read(config).collectDrops());
    }

    @Test void unknownExcludedOreIsRejectedWithItsPath() {
        YamlConfiguration config=new YamlConfiguration();
        config.set("vein-mining.excluded-ores",List.of("unobtainium"));
        var error=assertThrows(IllegalArgumentException.class,() -> VeinMining.Options.read(config));
        assertTrue(error.getMessage().startsWith("vein-mining.excluded-ores"));
    }

    @Test void lastSeedOfARenewableVeinIsKept() {
        var vein=RegrowthState.partition(UUID.randomUUID().toString(),java.util.stream.IntStream.range(0,20)
                .mapToObj(x->new Generation.OreCell(x,0,0,"minecraft:iron_ore")).toList()).getFirst();
        assertEquals(2,vein.required());
        for(int i=0;i<17;i++) vein.cells.get(i).state=1;
        assertFalse(vein.lastSeed(vein.cells.get(17)));
        vein.cells.get(17).state=1;
        assertTrue(vein.lastSeed(vein.cells.get(18)));
        assertFalse(vein.lastSeed(vein.cells.get(0)));
        vein.exhausted=true;
        assertFalse(vein.lastSeed(vein.cells.get(18)));
    }
}

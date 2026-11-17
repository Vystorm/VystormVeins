package at.esoren.vystormveins;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegrowthStateTest {
    private RegrowthState.Vein vein(int count) {
        return RegrowthState.partition(UUID.randomUUID().toString(),java.util.stream.IntStream.range(0,count)
                .mapToObj(x->new Generation.OreCell(x,0,0,"minecraft:iron_ore")).toList()).getFirst();
    }
    @Test void tenPercentIsRoundedUpAndExhaustionIsPermanent() {
        var v=vein(11); assertEquals(2,v.required());
        for(int i=0;i<9;i++) v.cells.get(i).state=1;
        v.checkSeeds(); assertFalse(v.exhausted);
        v.cells.get(9).state=1; v.checkSeeds(); assertTrue(v.exhausted);
        v.cells.forEach(c->c.state=0); v.checkSeeds(); assertTrue(v.exhausted);
    }
    @Test void exactlyTenPercentSurvives() {
        var v=vein(10); for(int i=0;i<9;i++) v.cells.get(i).state=1;
        v.checkSeeds(); assertFalse(v.exhausted);
        v.cells.getLast().state=1; v.checkSeeds(); assertTrue(v.exhausted);
    }
    @Test void warningAtEightyPercentMinedNeverRearmsAfterRegrowth() {
        var v=vein(10);
        for(int i=0;i<7;i++) v.cells.get(i).state=1;
        assertFalse(v.warningDue());
        v.cells.get(7).state=1; assertTrue(v.warningDue()); assertFalse(v.warningDue());
        v.cells.forEach(c->c.state=0); assertFalse(v.warningDue());
        v.cells.forEach(c->c.state=1); assertFalse(v.warningDue());
    }
    @Test void componentsMergeNormalAndDeepButSeparateDifferentOresAndGaps() {
        var groups=RegrowthState.partition(UUID.randomUUID().toString(),List.of(
                new Generation.OreCell(0,0,0,"minecraft:iron_ore"),
                new Generation.OreCell(1,0,0,"minecraft:deepslate_iron_ore"),
                new Generation.OreCell(2,0,0,"minecraft:coal_ore"),
                new Generation.OreCell(4,0,0,"minecraft:iron_ore")));
        assertEquals(List.of(2,1,1),groups.stream().map(v->v.cells.size()).toList());
    }
    @Test void sameOreFromDifferentNodesKeepsSeparateSettings() {
        var groups=RegrowthState.partition(UUID.randomUUID().toString(),List.of(
                new Generation.OreCell(0,0,0,"minecraft:iron_ore","small"),
                new Generation.OreCell(1,0,0,"minecraft:iron_ore","large")));
        assertEquals(2,groups.size());
        assertEquals(List.of("small","large"),groups.stream().map(v->v.deposit).toList());
    }
}

package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.LimitedRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class CompatibilityTest {
    static boolean legacyBiomes() { return Biome.class.isEnum(); }

    @Test @EnabledIf("legacyBiomes")
    void shippedClassesReadLegacyBiomeKeys() {
        Biome biome = mock(Biome.class);
        // Invoke the common interface even when the test is compiled against the newer API.
        when(((org.bukkit.Keyed) biome).getKey()).thenReturn(NamespacedKey.minecraft("plains"));
        World world = mock(World.class);
        LimitedRegion region = mock(LimitedRegion.class);
        when(world.getBiome(1, 2, 3)).thenReturn(biome);
        when(region.getBiome(1, 2, 3)).thenReturn(biome);
        assertEquals("minecraft:plains", Generation.world(world).biome(1, 2, 3));
        assertEquals("minecraft:plains", Generation.region(region, -64, 320).biome(1, 2, 3));
    }

    @Test void missingMaterialSelectsStoneIcon() {
        assertNull(Material.matchMaterial("minecraft:unavailable_future_ore"));
        assertEquals(Material.STONE, OreMap.icon("minecraft:unavailable_future_ore"));
    }

    @Test void availableMaterialKeepsItsIcon() {
        Material material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        try (var materials = mockStatic(Material.class, CALLS_REAL_METHODS)) {
            materials.when(() -> Material.matchMaterial("minecraft:available_ore")).thenReturn(material);
            assertSame(material, OreMap.icon("minecraft:available_ore"));
        }
    }
}

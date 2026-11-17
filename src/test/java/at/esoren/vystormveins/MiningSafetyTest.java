package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class MiningSafetyTest {
    private static final MiningSafety.Cell SAFE = new MiningSafety.Cell(false, false, false);
    private static final MiningSafety.Cell LAVA = new MiningSafety.Cell(true, false, false);
    private static final MiningSafety.Cell WATER = new MiningSafety.Cell(false, true, false);
    private static final MiningSafety.Cell FALLING = new MiningSafety.Cell(false, false, true);
    private static final MiningSafety.Options ALL = new MiningSafety.Options(true, true, true, true);

    private MiningSafety.Hazard check(Map<String, MiningSafety.Cell> cells, MiningSafety.Options options) {
        return MiningSafety.check((x,y,z) -> cells.getOrDefault(x+","+y+","+z, SAFE), 0, 10, 0, 320, options);
    }

    @Test void detectsLavaOnEveryExposedFaceButNotBelowOrDiagonally() {
        for (String at : List.of("0,11,0", "1,10,0", "-1,10,0", "0,10,1", "0,10,-1"))
            assertEquals(MiningSafety.Hazard.LAVA, check(Map.of(at, LAVA), ALL), at);
        assertNull(check(Map.of("0,9,0", LAVA, "1,11,1", LAVA), ALL));
    }

    @Test void waterAndFallingBlocksHaveIndependentSwitches() {
        assertEquals(MiningSafety.Hazard.WATER, check(Map.of("1,10,0", WATER), ALL));
        assertNull(check(Map.of("1,10,0", WATER), new MiningSafety.Options(true,true,false,true)));
        assertEquals(MiningSafety.Hazard.FALLING, check(Map.of("0,11,0", FALLING), ALL));
        assertNull(check(Map.of("1,10,0", FALLING), ALL));
        assertNull(check(Map.of("0,11,0", FALLING), new MiningSafety.Options(true,true,true,false)));
        assertNull(check(Map.of("1,10,0", LAVA), new MiningSafety.Options(true,false,true,true)));
    }

    @Test void disabledProtectionDoesNotReadTheWorld() {
        MiningSafety.Lookup lookup = mock(MiningSafety.Lookup.class);
        assertNull(MiningSafety.check(lookup,0,10,0,320,MiningSafety.Options.DEFAULTS));
        assertNull(MiningSafety.check(lookup,0,10,0,320,new MiningSafety.Options(true,false,false,false)));
        verifyNoInteractions(lookup);
    }

    @Test void unknownNeighboursStopButWorldCeilingIsNotRead() {
        assertEquals(MiningSafety.Hazard.UNLOADED, MiningSafety.check((x,y,z)->null,0,10,0,320,ALL));
        assertNull(MiningSafety.check((x,y,z)-> {
            assertTrue(y<320); return SAFE;
        },0,319,0,320,ALL));
        assertNull(MiningSafety.check((x,y,z)-> {
            assertEquals(11,y); return SAFE;
        },0,10,0,320,new MiningSafety.Options(true,false,false,true)));
    }

    @Test void worldAdapterDoesNotLoadChunksAndRecognizesWaterloggingAndGravity() {
        World world = mock(World.class);
        var lookup = MiningSafety.world(world, material -> material == Material.GRAVEL);
        assertNull(lookup.cell(-1,10,0));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        when(world.isChunkLoaded(-1,0)).thenReturn(true);
        Block block = mock(Block.class);
        when(world.getBlockAt(-1,10,0)).thenReturn(block);
        when(block.getType()).thenReturn(Material.OAK_SLAB);
        Waterlogged data = mock(Waterlogged.class);
        when(data.isWaterlogged()).thenReturn(true);
        when(block.getBlockData()).thenReturn(data);
        assertTrue(lookup.cell(-1,10,0).water());
        when(data.isWaterlogged()).thenReturn(false);
        assertFalse(lookup.cell(-1,10,0).water());
        when(block.getType()).thenReturn(Material.BUBBLE_COLUMN);
        assertTrue(lookup.cell(-1,10,0).water());
        when(block.getType()).thenReturn(Material.GRAVEL);
        assertTrue(lookup.cell(-1,10,0).gravity());
        when(block.getType()).thenReturn(Material.LAVA);
        assertTrue(lookup.cell(-1,10,0).lava());
    }

    @Test void migrationAddsDisabledDefaultsPreservesChoicesAndIsIdempotent() throws Exception {
        var config = new YamlConfiguration();
        config.set("worlds",List.of("custom"));
        assertTrue(MiningSafety.migrate(config));
        assertEquals(MiningSafety.Options.DEFAULTS,MiningSafety.Options.read(config));
        assertEquals(List.of("custom"),config.getStringList("worlds"));
        assertFalse(config.contains("vein-mining"));
        config.set("mining-safety.enabled",true);
        config.set("mining-safety.lava",false);
        assertFalse(MiningSafety.migrate(config));
        var restored = new YamlConfiguration();
        restored.loadFromString(config.saveToString());
        assertTrue(MiningSafety.Options.read(restored).enabled());
        assertFalse(MiningSafety.Options.read(restored).lava());
    }

    @Test void invalidValuesAreRejectedBeforeMigrationChangesAnything() {
        for (String key : List.of("enabled","lava","water","falling-blocks")) {
            var config = new YamlConfiguration();
            config.set("mining-safety."+key,"yes please");
            String before = config.saveToString();
            var error = assertThrows(IllegalArgumentException.class,()->MiningSafety.migrate(config));
            assertTrue(error.getMessage().contains("mining-safety."+key));
            assertEquals(before,config.saveToString());
        }
        var config = new YamlConfiguration();
        config.set("mining-safety",false);
        assertThrows(IllegalArgumentException.class,()->MiningSafety.Options.read(config));
    }

    @Test void bundledConfigsEnableLavaAndGravityButNotWater() {
        for (String name : List.of("config.yml","presets/mixed.yml","presets/rare-large.yml","presets/frequent-small.yml")) {
            var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(name));
            var config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(new MiningSafety.Options(true,true,false,true),MiningSafety.Options.read(config),name);
            assertFalse(MiningSafety.migrate(config));
        }
    }

    @Test void invalidSafetyReloadRetainsActiveOptions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        java.nio.file.Files.writeString(folder.resolve("config.yml"),"worlds: [world]\ndeposits: {}\nmining-safety:\n  lava: nope\n");
        VeinsPlugin plugin = mock(VeinsPlugin.class,CALLS_REAL_METHODS);
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(java.util.logging.Logger.getAnonymousLogger()).when(plugin).getLogger();
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        var field = VeinsPlugin.class.getDeclaredField("miningSafety");
        field.setAccessible(true); field.set(plugin,ALL);
        var sender = mock(org.bukkit.command.CommandSender.class);
        plugin.reloadCommand(sender);
        assertSame(ALL,plugin.miningSafety());
        verify(plugin).say(sender,"reload-failed","mining-safety.lava must be true or false");
        verify(plugin,never()).reloadConfig();
    }

    @Test void automaticMiningStopsEntireBatchAndCanResumeAfterHazardIsRemoved() throws Exception {
        VeinsPlugin plugin = mock(VeinsPlugin.class);
        var config = new YamlConfiguration();
        config.set("vein-mining.enabled",true);
        config.set("vein-mining.collect-drops",false);
        var options = VeinMining.Options.read(config);
        when(plugin.veinMining()).thenReturn(options);
        when(plugin.miningSafety()).thenReturn(ALL);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission("vystormveins.veinmine")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.getOrDefault(any(NamespacedKey.class),eq(PersistentDataType.BYTE),anyByte())).thenReturn((byte)0);
        var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var tool = mock(ItemStack.class);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getMaxHeight()).thenReturn(320);
        Block stone = mock(Block.class), ore = mock(Block.class), lava = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(ore.getType()).thenReturn(Material.IRON_ORE);
        when(ore.isPreferredTool(tool)).thenReturn(true);
        when(lava.getType()).thenReturn(Material.LAVA);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(stone);
        when(world.getBlockAt(1,10,0)).thenReturn(ore);
        when(world.getBlockAt(2,10,0)).thenReturn(ore);
        when(world.getBlockAt(3,10,0)).thenReturn(ore);
        when(world.getBlockAt(2,11,0)).thenReturn(lava);
        when(player.breakBlock(ore)).thenReturn(true);
        when(plugin.word(player,"safety-lava")).thenReturn("lava");
        var mine = VeinMiner.class.getDeclaredMethod("mine",Player.class,World.class,String.class,Set.class,List.class,VeinMining.Options.class,Location.class);
        mine.setAccessible(true);
        var miner = new VeinMiner(plugin);
        var vein = List.of(new int[]{1,10,0},new int[]{2,10,0},new int[]{3,10,0});
        // Material.hasGravity needs a live server registry; keep world reads and the mining loop real.
        var lookup = MiningSafety.world(world, material -> false);
        try (var safety = mockStatic(MiningSafety.class, CALLS_REAL_METHODS)) {
            safety.when(() -> MiningSafety.world(world)).thenReturn(lookup);
            mine.invoke(miner,player,world,"iron",Set.of(),vein,options,null);
            verify(player,times(1)).breakBlock(ore);
            verify(plugin).say(player,"safety-stopped",1,"lava",2,10,0);
            when(world.getBlockAt(2,11,0)).thenReturn(stone);
            mine.invoke(miner,player,world,"iron",Set.of(),vein,options,null);
            verify(player,times(4)).breakBlock(ore);
        }
    }
}

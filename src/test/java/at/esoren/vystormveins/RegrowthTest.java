package at.esoren.vystormveins;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegrowthTest {
    @TempDir Path dir;
    VeinsPlugin plugin; Server server; World world; PluginManager plugins; Player player;
    Regrowth tracker; UUID worldId=UUID.randomUUID();
    Map<Integer,AtomicReference<Material>> materials=new HashMap<>();
    Map<Integer,Block> blocks=new HashMap<>();
    List<Runnable> delayed=new ArrayList<>();
    @BeforeEach void setup() {
        plugin=mock(VeinsPlugin.class); server=mock(Server.class); plugins=mock(PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile()); when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        var config=new YamlConfiguration(); config.set("regrowth.enabled",true); config.set("regrowth.warning-message","80% mined, leave {minimum}");
        when(plugin.getConfig()).thenReturn(config);
        when(server.getPluginManager()).thenReturn(plugins);
        BukkitScheduler scheduler=mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(i->{ delayed.add(i.getArgument(1)); return mock(BukkitTask.class); });
        world=mock(World.class); when(world.getUID()).thenReturn(worldId); when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getNearbyEntities(any(org.bukkit.util.BoundingBox.class))).thenReturn(List.of());
        for(int x=0;x<10;x++) {
            Block b=mock(Block.class); blocks.put(x,b);
            AtomicReference<Material> type=new AtomicReference<>(Material.IRON_ORE); materials.put(x,type);
            when(b.getType()).thenAnswer(i->type.get());
            doAnswer(i->{type.set(i.getArgument(0));return null;}).when(b).setType(any(Material.class),eq(false));
            when(b.getWorld()).thenReturn(world); when(b.getX()).thenReturn(x);
            when(world.getBlockAt(x,0,0)).thenReturn(b);
            when(b.getState()).thenAnswer(i->{
                BlockState state=mock(BlockState.class); AtomicReference<Material> value=new AtomicReference<>(type.get());
                when(state.getType()).thenAnswer(j->value.get());
                doAnswer(j->{value.set(j.getArgument(0));return null;}).when(state).setType(any());
                return state;
            });
        }
        player=mock(Player.class); when(player.getLocation()).thenReturn(new Location(world,0,0,0));
        when(player.getWorld()).thenReturn(world); when(player.isOnline()).thenReturn(true);
        tracker=new Regrowth(plugin, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.schedule.TaskGroupInterface.DEFAULT); tracker.configure(new Settings(Set.of("world"),false,List.of()));
        tracker.generated(worldId,java.util.stream.IntStream.range(0,10)
                .mapToObj(x->new Generation.OreCell(x,0,0,"minecraft:iron_ore")).toList());
    }
    void mine(int x) {
        BlockBreakEvent event=mock(BlockBreakEvent.class); when(event.getBlock()).thenReturn(blocks.get(x));
        when(event.getPlayer()).thenReturn(player); fire(event); materials.get(x).set(Material.AIR);
    }
    /** Calls the tracker's handlers the way Vystorm Core's stage dispatcher does. */
    <E extends org.bukkit.event.Event> E fire(E event) { return Fired.withCore(plugin,order->{tracker.handleBlockEvents(order);tracker.handleSharedEvents(order);}).fire(event); }
    RegrowthState data() throws Exception {
        var field=Regrowth.class.getDeclaredField("state"); field.setAccessible(true); return (RegrowthState)field.get(tracker);
    }
    void grow() throws Exception {
        var method=Regrowth.class.getDeclaredMethod("grow",RegrowthState.Vein.class,long.class); method.setAccessible(true);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getWorld(worldId)).thenReturn(world);
            method.invoke(tracker,data().veins.getFirst(),System.currentTimeMillis());
        }
    }
    @AfterEach void close() { tracker.close(); }
    @Test void oneOreReturnsPerIntervalWithTenPercentLeft() throws Exception {
        for(int x=0;x<9;x++) mine(x);
        grow(); assertEquals(2,materials.values().stream().filter(v->v.get()==Material.IRON_ORE).count());
        assertFalse(data().veins.getFirst().exhausted);
    }
    @Test void exhaustionAndWarningsSurviveRestart() throws Exception {
        for(int x=0;x<10;x++) mine(x);
        tracker.close(); tracker=new Regrowth(plugin, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.schedule.TaskGroupInterface.DEFAULT);
        assertTrue(data().veins.getFirst().exhausted); assertTrue(data().veins.getFirst().warned);
    }
    @Test void cancelledBreakDoesNotWarnOrReduceSeedCount() throws Exception {
        BlockBreakEvent event=mock(BlockBreakEvent.class); when(event.isCancelled()).thenReturn(true);
        fire(event); verify(event,never()).getBlock(); // Core does not call the handler for a cancelled break ...
        tracker.broken(event); verifyNoInteractions(player); // ... and the handler ignores one all the same
        mine(0); assertEquals(9,data().veins.getFirst().cells.stream().filter(c->c.state==0).count());
    }
    @Test void threeBassSoundsAndOneMessageAtEighthBlockOfTen() {
        for(int i=0;i<7;i++) mine(i);
        verify(player,never()).playSound(any(Location.class),anyString(),anyFloat(),anyFloat());
        verify(player,never()).sendMessage(anyString());
        mine(7); mine(8); mine(9);
        assertEquals(2,delayed.size()); delayed.forEach(Runnable::run);
        verify(player,times(3)).playSound(any(Location.class),eq("minecraft:block.note_block.bass"),eq(.8f),eq(.6f));
        verify(player,times(1)).sendMessage(contains("80%"));
    }
    @Test void unloadedChunksAreNeverReadOrLoaded() throws Exception {
        mine(0); when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        grow(); verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        assertEquals(Material.AIR,materials.get(0).get());
    }
    @Test void buildsAndFluidsAreNotOverwritten() throws Exception {
        mine(0); materials.get(0).set(Material.CHEST); grow();
        assertEquals(Material.CHEST,materials.get(0).get());
        assertEquals(2,data().veins.getFirst().cells.get(0).state);
    }
    @Test void cancelledSpreadPreventsRegrowth() throws Exception {
        mine(0);
        doAnswer(i->{ ((BlockSpreadEvent)i.getArgument(0)).setCancelled(true); return null; }).when(plugins).callEvent(any(BlockSpreadEvent.class));
        grow(); assertEquals(Material.AIR,materials.get(0).get());
    }
    @Test void placingOreBackDoesNotRestoreSeedEligibility() throws Exception {
        mine(0); BlockPlaceEvent place=mock(BlockPlaceEvent.class);
        when(place.canBuild()).thenReturn(true); when(place.getBlockPlaced()).thenReturn(blocks.get(0));
        fire(place); materials.get(0).set(Material.IRON_ORE);
        grow(); assertEquals(2,data().veins.getFirst().cells.get(0).state);
    }
    @Test void corruptStateIsNotOverwritten() throws Exception {
        Path file=dir.resolve("regrowth.json"); tracker.close();
        Files.writeString(file,"not json");
        assertThrows(RuntimeException.class,()->new Regrowth(plugin, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.schedule.TaskGroupInterface.DEFAULT));
        assertEquals("not json",Files.readString(file));
    }
    @Test void warningCanBeDisabled() {
        plugin.getConfig().set("regrowth.warning-sound",false);
        tracker.configure(new Settings(Set.of("world"),false,List.of()));
        for(int i=0;i<8;i++) mine(i);
        verify(player,never()).playSound(any(Location.class),anyString(),anyFloat(),anyFloat());
        verify(player).sendMessage(anyString()); assertTrue(delayed.isEmpty());
    }
    @Test void exhaustedVeinCannotBeReactivatedByApply() throws Exception {
        for(int x=0;x<10;x++) mine(x);
        tracker.generated(worldId,java.util.stream.IntStream.range(0,10)
                .mapToObj(x->new Generation.OreCell(x,0,0,"minecraft:iron_ore")).toList());
        mine(0); grow();
        assertEquals(1,data().veins.size()); assertTrue(data().veins.getFirst().exhausted);
        assertTrue(materials.values().stream().allMatch(v->v.get()==Material.AIR));
    }
    /** Collects the warnings the tracker writes to the console. */
    List<String> warnings() {
        List<String> lines=new ArrayList<>();
        var logger=java.util.logging.Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        when(plugin.getLogger()).thenReturn(logger);
        return lines;
    }
    void vein(int y, int size) {
        tracker.generated(worldId,java.util.stream.IntStream.range(0,size)
                .mapToObj(x->new Generation.OreCell(x,y,0,"minecraft:iron_ore")).toList());
        tracker.counts(); // drains the queue on the "main thread"
    }
    @Test void reapplyingTrackedVeinsIsNotReportedAsTheLimit() {
        List<String> lines=warnings();
        tracker.counts();
        vein(0,10); // the same ten cells as in setup, as /vveins apply would generate them again
        assertEquals(1,tracker.counts()[0]);
        assertEquals(List.of(),lines);
    }
    @Test void trackingLimitIsReportedOncePerReloadWithTheNumbers() {
        List<String> lines=warnings();
        plugin.getConfig().set("regrowth.max-tracked-blocks",100); tracker.configure(new Settings(Set.of("world"),false,List.of()));
        assertEquals(100,tracker.maxCells());
        vein(10,50); vein(20,50); vein(30,50);
        assertEquals(60,tracker.counts()[2]);
        assertEquals(1,lines.size(),lines.toString());
        assertTrue(lines.getFirst().contains("60 of 100") && lines.getFirst().contains("regrowth.max-tracked-blocks"),lines.getFirst());
        tracker.configure(new Settings(Set.of("world"),false,List.of())); vein(40,50);
        assertEquals(2,lines.size(),"a reload reports it again");
    }
    @Test void externalRemovalOfLastSeedPreventsGrowth() throws Exception {
        for(int x=0;x<9;x++) mine(x);
        materials.get(9).set(Material.AIR); grow();
        assertTrue(data().veins.getFirst().exhausted);
        assertTrue(materials.values().stream().allMatch(v->v.get()==Material.AIR));
    }
}

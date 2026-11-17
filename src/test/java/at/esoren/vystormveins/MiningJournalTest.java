package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.persistence.*;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class MiningJournalTest {
    static PersistentDataContainer data(Map<NamespacedKey,Object> values){
        PersistentDataContainer data=mock(PersistentDataContainer.class);
        when(data.get(any(),any())).thenAnswer(i->values.get(i.getArgument(0)));
        when(data.getOrDefault(any(),any(),any())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(2)));
        when(data.has(any(),any())).thenAnswer(i->values.containsKey(i.getArgument(0)));
        doAnswer(i->{Object value=i.getArgument(2);values.put(i.getArgument(0),value instanceof long[] a?a.clone():value instanceof int[] a?a.clone():value);return null;}).when(data).set(any(),any(),any());
        return data;
    }
    static class Fixture {
        VeinsPlugin plugin=mock(VeinsPlugin.class);Server server=mock(Server.class);BukkitScheduler scheduler=mock(BukkitScheduler.class);
        Player player=mock(Player.class);World world=mock(World.class);Chunk chunk=mock(Chunk.class);Block block=mock(Block.class);
        List<Runnable> tasks=new ArrayList<>();Map<NamespacedKey,Object> playerSaved=new HashMap<>(),chunkSaved=new HashMap<>();
        AtomicReference<Material> material=new AtomicReference<>(Material.DIAMOND_ORE);MiningJournal journal;
        Fixture(){
            when(plugin.getServer()).thenReturn(server);when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(i->{tasks.add(i.getArgument(1));return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            when(player.getPersistentDataContainer()).thenAnswer(i->data(playerSaved));when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.isOnline()).thenReturn(true);when(player.hasPermission("vystormveins.journal")).thenReturn(true);
            when(world.getUID()).thenReturn(UUID.randomUUID());when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            when(block.getWorld()).thenReturn(world);when(block.getChunk()).thenReturn(chunk);when(block.getY()).thenReturn(-32);
            when(block.getType()).thenAnswer(i->material.get());when(chunk.getPersistentDataContainer()).thenAnswer(i->data(chunkSaved));
            when(plugin.oreName(any(),any())).thenAnswer(i->i.getArgument(1));journal=new MiningJournal(plugin);
        }
        void drain(){List<Runnable> copied=List.copyOf(tasks);tasks.clear();copied.forEach(Runnable::run);}
        long diamonds(){return MiningJournal.counts(player.getPersistentDataContainer())[6];}
        /** Calls the journal's handlers the way Vystorm Core's stage dispatcher does. */
        <E extends org.bukkit.event.Event> E fire(E event){return Fired.withCore(plugin,order->{journal.handleBlockEvents(order);journal.handleSharedEvents(order);}).fire(event);}
        void harvest(){fire(new BlockBreakEvent(block,player));material.set(Material.AIR);drain();}
    }
    @Test void countsCompletedBreakOnlyAndPersistsPlayerAndChunkLedgerAcrossServiceRestart(){
        Fixture f=new Fixture();f.harvest();assertEquals(1,f.diamonds());
        f.journal=new MiningJournal(f.plugin);when(f.player.getPersistentDataContainer()).thenAnswer(i->data(f.playerSaved));
        when(f.chunk.getPersistentDataContainer()).thenAnswer(i->data(f.chunkSaved));f.material.set(Material.DIAMOND_ORE);f.harvest();assertEquals(1,f.diamonds());
        verify(f.plugin,times(1)).say(f.player,"journal-unlocked","diamond",1L,1);
    }
    @Test void lateCancellationAndUnchangedBlockAndCreativeAndUnloadNeverCredit(){
        Fixture f=new Fixture();BlockBreakEvent cancelled=f.fire(new BlockBreakEvent(f.block,f.player));cancelled.setCancelled(true);f.material.set(Material.AIR);f.drain();assertEquals(0,f.diamonds());
        f.material.set(Material.DIAMOND_ORE);f.fire(new BlockBreakEvent(f.block,f.player));f.drain();assertEquals(0,f.diamonds());
        BlockBreakEvent denied=new BlockBreakEvent(f.block,f.player);denied.setCancelled(true);f.fire(denied);assertTrue(f.tasks.isEmpty(),"a denied break is not even looked at");
        when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);f.harvest();assertEquals(0,f.diamonds());
        when(f.player.getGameMode()).thenReturn(GameMode.SURVIVAL);f.material.set(Material.DIAMOND_ORE);f.fire(new BlockBreakEvent(f.block,f.player));
        f.material.set(Material.AIR);when(f.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);f.drain();assertEquals(0,f.diamonds());
    }
    @Test void placedAndPistonMovedOresArePermanentlyExcluded(){
        Fixture placed=new Fixture();BlockPlaceEvent event=mock(BlockPlaceEvent.class);when(event.getBlockPlaced()).thenReturn(placed.block);placed.fire(event);placed.harvest();assertEquals(0,placed.diamonds());
        Fixture piston=new Fixture();Block target=mock(Block.class);when(target.getWorld()).thenReturn(piston.world);when(target.getChunk()).thenReturn(piston.chunk);when(target.getX()).thenReturn(1);when(target.getY()).thenReturn(-32);
        when(piston.block.getRelative(BlockFace.EAST)).thenReturn(target);BlockPistonExtendEvent movement=mock(BlockPistonExtendEvent.class);when(movement.getBlocks()).thenReturn(List.of(piston.block));when(movement.getDirection()).thenReturn(BlockFace.EAST);
        piston.fire(movement);assertTrue(MiningJournal.seen(piston.chunk.getPersistentDataContainer(),MiningJournal.coordinate(target)));piston.harvest();assertEquals(0,piston.diamonds());
    }
    @Test void distinctCoordinatesAndBoundedSaturatedLedgerCannotBecomeFarm(){
        PersistentDataContainer p=data(new HashMap<>());for(int i=0;i<MiningJournal.MAX_POSITIONS;i++)assertTrue(MiningJournal.mark(p,i));
        assertFalse(MiningJournal.mark(p,0));assertFalse(MiningJournal.mark(p,5000));assertTrue(MiningJournal.seen(p,6000));
        Block low=mock(Block.class),high=mock(Block.class);when(low.getY()).thenReturn(-64);when(high.getY()).thenReturn(1087);assertNotEquals(MiningJournal.coordinate(low),MiningJournal.coordinate(high));
    }
    @Test void goalsArePersonalCosmeticAndMergeOreVariants(){
        Fixture f=new Fixture();for(int i=0;i<16;i++)f.journal.credit(f.player,"diamond");
        assertEquals(16,f.diamonds());assertEquals(2,MiningJournal.tier(16));assertEquals("gold",MiningJournal.family(Material.NETHER_GOLD_ORE));assertEquals("diamond",MiningJournal.family(Material.DEEPSLATE_DIAMOND_ORE));
        f.journal.credit(f.player,"iron");f.journal.credit(f.player,"coal");verify(f.plugin).say(f.player,"journal-collection",3,10);
        Player other=mock(Player.class);when(other.getPersistentDataContainer()).thenAnswer(i->data(new HashMap<>()));assertEquals(0,MiningJournal.discovered(MiningJournal.counts(other.getPersistentDataContainer())));
        verify(f.player,never()).getInventory();verify(f.player,never()).giveExp(anyInt());
    }
    @Test void unauthorizedViewerDoesNotReadOthersData(){Fixture f=new Fixture();when(f.player.hasPermission("vystormveins.journal")).thenReturn(false);f.journal.show(f.player);verify(f.plugin).say(f.player,"denied");verify(f.player,never()).getPersistentDataContainer();}
}

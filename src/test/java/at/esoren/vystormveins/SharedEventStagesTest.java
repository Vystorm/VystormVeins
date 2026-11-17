package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/**
 * The handlers of the other shared events (clicks with the prospecting rod, pistons, explosions), registered through
 * {@link EventOrder} the way {@code VeinsPlugin.onEnable} does it. Every rule must hold both with Vystorm Core (Core's
 * real stage dispatcher) and without it (plain Bukkit listeners).
 */
class SharedEventStagesTest {
    private final Block block = mock(Block.class);

    // the plugin and the feature listeners with their real registration and silent handlers
    private final VeinsPlugin plugin = mock(VeinsPlugin.class, CALLS_REAL_METHODS);
    private final MiningJournal journal = mock(MiningJournal.class, CALLS_REAL_METHODS);
    private final Regrowth regrowth = mock(Regrowth.class, CALLS_REAL_METHODS);

    SharedEventStagesTest() {
        doNothing().when(plugin).prospect(any());
        doNothing().when(plugin).claimVein(any());
        doNothing().when(journal).extended(any());
        doNothing().when(journal).retracted(any());
        doNothing().when(regrowth).exploded(any(EntityExplodeEvent.class));
        doNothing().when(regrowth).exploded(any(BlockExplodeEvent.class));
        doNothing().when(regrowth).piston(any(BlockPistonExtendEvent.class));
        doNothing().when(regrowth).piston(any(BlockPistonRetractEvent.class));
    }

    /** The registrations of VeinsPlugin.onEnable (regrowth only with Core), in its order. */
    private void register(EventOrder order) {
        plugin.handleSharedEvents(order);
        journal.handleSharedEvents(order);
        regrowth.handleSharedEvents(order);
    }

    private PlayerInteractEvent click(Action action) {
        return new PlayerInteractEvent(mock(Player.class), action, null, action == Action.RIGHT_CLICK_BLOCK ? block : null, BlockFace.UP);
    }

    private BlockExplodeEvent explosion() {
        return new BlockExplodeEvent(block, mock(BlockState.class), new ArrayList<>(List.of(block)), 1f, ExplosionResult.DESTROY);
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClickWithTheRod_whenFired_thenProspectingComesBeforeClaimingAndBothAlsoSeeAClickIntoTheAir(Fired.Mode mode) {
        Fired fired = Fired.in(mode, plugin, this::register);

        PlayerInteractEvent onOre = fired.fire(click(Action.RIGHT_CLICK_BLOCK));
        PlayerInteractEvent inAir = fired.fire(click(Action.RIGHT_CLICK_AIR));

        assertFalse(onOre.isCancelled());
        assertTrue(inAir.isCancelled(), "the server delivers a click into the air as cancelled");
        InOrder order = inOrder(plugin);
        order.verify(plugin).prospect(onOre);
        order.verify(plugin).claimVein(onOre);
        order.verify(plugin).prospect(inAir);
        order.verify(plugin).claimVein(inAir);
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClickDeniedByAProtection_whenFired_thenTheRodHandlersStillDecideThemselves(Fired.Mode mode) {
        Fired fired = Fired.in(mode, plugin, order -> {
            order.handle(PlayerInteractEvent.class, EventOrder.Stage.PROTECT, event -> event.setCancelled(true));
            register(order);
        });

        PlayerInteractEvent denied = fired.fire(click(Action.RIGHT_CLICK_BLOCK));

        // as before the move: ignoreCancelled = false, now receiveCancelled = true
        assertTrue(denied.isCancelled());
        verify(plugin).prospect(denied);
        verify(plugin).claimVein(denied);
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClaimMadeWithTheRod_whenFired_thenBookkeepingOfTheSameClickDoesNotRun(Fired.Mode mode) {
        List<String> calls = new ArrayList<>();
        doAnswer(call -> { call.getArgument(0, PlayerInteractEvent.class).setCancelled(true); return null; }).when(plugin).claimVein(any());
        Fired fired = Fired.in(mode, plugin, order -> {
            register(order);
            order.handle(PlayerInteractEvent.class, EventOrder.Stage.OBSERVE, event -> calls.add("observe"));
        });

        fired.fire(click(Action.RIGHT_CLICK_BLOCK));

        assertTrue(calls.isEmpty(), "the claim consumed the click");
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenPistonOrExplosion_whenDeniedOrAllowed_thenTheBookkeepingOnlySeesTheAllowedOne(Fired.Mode mode) {
        boolean[] deny = {true};
        Fired fired = Fired.in(mode, plugin, order -> {
            order.handle(BlockPistonExtendEvent.class, EventOrder.Stage.PROTECT, event -> event.setCancelled(deny[0]));
            order.handle(BlockExplodeEvent.class, EventOrder.Stage.PROTECT, event -> event.setCancelled(deny[0]));
            register(order);
        });

        fired.fire(new BlockPistonExtendEvent(block, List.of(block), BlockFace.UP));
        fired.fire(explosion());
        verify(journal, never()).extended(any());
        verify(regrowth, never()).piston(any(BlockPistonExtendEvent.class));
        verify(regrowth, never()).exploded(any(BlockExplodeEvent.class));

        deny[0] = false;
        BlockPistonExtendEvent extend = fired.fire(new BlockPistonExtendEvent(block, List.of(block), BlockFace.UP));
        BlockExplodeEvent explosion = fired.fire(explosion());
        InOrder order = inOrder(journal, regrowth);
        order.verify(journal).extended(extend);     // OBSERVE, in registration order
        order.verify(regrowth).piston(extend);
        order.verify(regrowth).exploded(explosion);
    }

    @Test
    void givenNoCore_whenRegistered_thenTheRodListensAtHighForCancelledClicksTooAndTheBookkeepingAtMonitor() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        org.mockito.Mockito.doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);

        register(new EventOrder(plugin, null));

        verify(manager, times(2)).registerEvent(eq(PlayerInteractEvent.class), any(), eq(EventPriority.HIGH), any(), eq(plugin), eq(false));
        verify(manager, times(2)).registerEvent(eq(BlockPistonExtendEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager, times(2)).registerEvent(eq(BlockPistonRetractEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager).registerEvent(eq(EntityExplodeEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager).registerEvent(eq(BlockExplodeEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager, times(8)).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
    }
}

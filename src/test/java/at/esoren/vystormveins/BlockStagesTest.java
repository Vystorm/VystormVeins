package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/**
 * The block break and place handlers of VystormVeins, registered through {@link EventOrder} the way
 * {@code VeinsPlugin.onEnable} does it. Every rule must hold both with Vystorm Core (Core's real stage dispatcher) and
 * without it (plain Bukkit listeners): the handler methods are the same, only the registration differs.
 */
class BlockStagesTest {
    private static final UUID OWNER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final World world = mock(World.class);
    private final Block block = mock(Block.class);

    // the plugin with its real claim handlers; the feature listeners with their real registration and silent handlers
    private final VeinsPlugin plugin = mock(VeinsPlugin.class, CALLS_REAL_METHODS);
    private final MiningClaims claims = new MiningClaims();
    private final VeinMiner miner = mock(VeinMiner.class, CALLS_REAL_METHODS);
    private final DeepVeins deep = mock(DeepVeins.class, CALLS_REAL_METHODS);
    private final MiningJournal journal = mock(MiningJournal.class, CALLS_REAL_METHODS);
    private final long key = MiningClaims.key(3, -50, 7);

    BlockStagesTest() throws Exception {
        Field field = VeinsPlugin.class.getDeclaredField("claims");
        field.setAccessible(true);
        field.set(plugin, claims);
        doNothing().when(plugin).sayBar(any(), any(), any(Object[].class));
        org.mockito.Mockito.doReturn(new YamlConfiguration()).when(plugin).getConfig();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(3);
        when(block.getY()).thenReturn(-50);
        when(block.getZ()).thenReturn(7);
        when(block.getType()).thenReturn(Material.ANCIENT_DEBRIS);
        when(block.getLocation()).thenAnswer(call -> new Location(world, 3, -50, 7));
        doNothing().when(miner).broken(any());
        doNothing().when(miner).extraBroken(any());
        doNothing().when(deep).place(any());
        doNothing().when(deep).mine(any());
        doNothing().when(deep).mined(any());
        doNothing().when(journal).placed(any());
        doNothing().when(journal).broken(any());
        claims.claim(OWNER, "Owner", worldId, "diamond", List.of(key), 0, Long.MAX_VALUE);
    }

    private static Player player(UUID id, World world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 3, -50, 7));
        return player;
    }

    /** The registrations of VeinsPlugin.onEnable, in its order. */
    private Fired everyListener(Fired.Mode mode) {
        return Fired.in(mode, plugin, order -> {
            plugin.handleBlockEvents(order);
            miner.handleBlockEvents(order);
            deep.handleBlockEvents(order);
            journal.handleBlockEvents(order);
        });
    }

    private boolean claimed() { return claims.at(worldId, key, 1).isPresent(); }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClaimedVein_whenAStrangerMinesIt_thenItIsDeniedAndNoFeatureOrBookkeepingRuns(Fired.Mode mode) {
        Player stranger = player(STRANGER, world);

        assertTrue(everyListener(mode).fire(new BlockBreakEvent(block, stranger)).isCancelled());

        verify(plugin).sayBar(eq(stranger), eq("claim-blocked"), any(Object[].class));
        verify(deep, never()).mine(any());
        verify(miner, never()).extraBroken(any());
        verify(miner, never()).broken(any());
        verify(deep, never()).mined(any());
        verify(journal, never()).broken(any());
        assertTrue(claimed(), "the claim does not shrink for a block that was not mined");
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClaimedVein_whenItsOwnerMinesIt_thenRulesFinishAndBookkeepingRunInThatOrderAndTheClaimShrinks(Fired.Mode mode) {
        BlockBreakEvent event = everyListener(mode).fire(new BlockBreakEvent(block, player(OWNER, world)));

        assertFalse(event.isCancelled());
        InOrder order = inOrder(deep, miner, journal);
        order.verify(deep).mine(event);            // ADJUST
        order.verify(miner).extraBroken(event);    // FINISH
        order.verify(miner).broken(event);         // OBSERVE, in registration order
        order.verify(deep).mined(event);
        order.verify(journal).broken(event);
        assertFalse(claimed(), "the mined block left the claim");
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenClaimedVein_whenALaterHandlerDeniesTheOwnersBreak_thenTheClaimKeepsTheBlock(Fired.Mode mode) {
        Fired fired = Fired.in(mode, plugin, order -> {
            plugin.handleBlockEvents(order);
            order.handle(BlockBreakEvent.class, EventOrder.Stage.ADJUST, event -> event.setCancelled(true));
        });

        assertTrue(fired.fire(new BlockBreakEvent(block, player(OWNER, world))).isCancelled());

        assertTrue(claimed());
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenPlacement_whenDeniedOrAllowed_thenTheBookkeepingOnlySeesTheAllowedOne(Fired.Mode mode) {
        boolean[] deny = {true};
        Fired fired = Fired.in(mode, plugin, order -> {
            order.handle(BlockPlaceEvent.class, EventOrder.Stage.PROTECT, event -> event.setCancelled(deny[0]));
            deep.handleBlockEvents(order);
            journal.handleBlockEvents(order);
        });
        Player placer = player(OWNER, world);

        fired.fire(new BlockPlaceEvent(block, mock(BlockState.class), block, mock(ItemStack.class), placer, true, EquipmentSlot.HAND));
        verify(deep, never()).place(any());
        verify(journal, never()).placed(any());

        deny[0] = false;
        fired.fire(new BlockPlaceEvent(block, mock(BlockState.class), block, mock(ItemStack.class), placer, true, EquipmentSlot.HAND));
        verify(deep).place(any());
        verify(journal).placed(any());
    }

    @ParameterizedTest
    @EnumSource(Fired.Mode.class)
    void givenDeepVeinCore_whenMinedAloneOrAsATeam_thenItIsRefusedWithoutRewardOrSharedOnlyIfTheBreakHappens(Fired.Mode mode) throws Exception {
        VeinsPlugin veins = mock(VeinsPlugin.class);
        when(veins.getConfig()).thenReturn(new YamlConfiguration());
        when(veins.settings()).thenReturn(new Settings(Set.of("world"), false, List.of()));
        DeepVeins real = new DeepVeins(veins);
        // two miners needed for ancient debris below y -40; set directly, reading core blocks needs a server registry
        Field options = DeepVeins.class.getDeclaredField("options");
        options.setAccessible(true);
        options.set(real, new DeepVeins.Options(true, Set.of("minecraft:ancient_debris"), -40, 2, 12, 15));
        boolean[] deny = {false};
        Fired fired = Fired.in(mode, veins, order -> {
            real.handleBlockEvents(order);
            order.handle(BlockBreakEvent.class, EventOrder.Stage.FINISH, event -> { if (deny[0]) event.setCancelled(true); });
        });
        Player first = player(OWNER, world), second = player(STRANGER, world);

        // alone: refused by the rule, nobody is rewarded
        when(world.getPlayers()).thenReturn(List.of(first));
        assertTrue(fired.fire(new BlockBreakEvent(block, first)).isCancelled());
        verify(veins).sayBar(first, "deep-needs", 2, 1, 1);
        verify(first, never()).giveExp(anyInt());

        // as a team, but another plugin denies the break after the rule: still no reward
        when(world.getPlayers()).thenReturn(List.of(first, second));
        deny[0] = true;
        assertTrue(fired.fire(new BlockBreakEvent(block, first)).isCancelled());
        verify(first, never()).giveExp(anyInt());
        verify(second, never()).giveExp(anyInt());

        // as a team and allowed: both share the experience
        deny[0] = false;
        assertFalse(fired.fire(new BlockBreakEvent(block, first)).isCancelled());
        verify(first).giveExp(15);
        verify(second).giveExp(15);
    }

    @Test
    void givenNoCore_whenRegistered_thenEveryHandlerIsABukkitListenerAtItsStagePriorityThatIgnoresCancelledEvents() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        org.mockito.Mockito.doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        EventOrder order = new EventOrder(plugin, null);
        assertFalse(order.ordered());

        plugin.handleBlockEvents(order);
        miner.handleBlockEvents(order);
        deep.handleBlockEvents(order);
        journal.handleBlockEvents(order);

        // claim PROTECT, deep vein rule ADJUST, collected experience FINISH, five times bookkeeping OBSERVE
        verify(manager).registerEvent(eq(BlockBreakEvent.class), any(), eq(EventPriority.LOW), any(), eq(plugin), eq(true));
        verify(manager).registerEvent(eq(BlockBreakEvent.class), any(), eq(EventPriority.NORMAL), any(), eq(plugin), eq(true));
        verify(manager).registerEvent(eq(BlockBreakEvent.class), any(), eq(EventPriority.HIGHEST), any(), eq(plugin), eq(true));
        verify(manager, times(4)).registerEvent(eq(BlockBreakEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager, times(2)).registerEvent(eq(BlockPlaceEvent.class), any(), eq(EventPriority.MONITOR), any(), eq(plugin), eq(true));
        verify(manager, times(9)).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void givenCore_whenRegistered_thenThePluginRegistersNoListenerOfItsOwnAndItsStagesMatchCores() {
        Server server = mock(Server.class);
        org.mockito.Mockito.doReturn(server).when(plugin).getServer();

        Fired.withCore(plugin, order -> {
            assertTrue(order.ordered());
            plugin.handleBlockEvents(order);
            miner.handleBlockEvents(order);
            deep.handleBlockEvents(order);
            journal.handleBlockEvents(order);
        });

        verifyNoInteractions(server);
        for (EventOrder.Stage stage : EventOrder.Stage.values())
            assertEquals(EventStage.valueOf(stage.name()).bukkit(), stage.bukkit(), stage.name());
    }
}

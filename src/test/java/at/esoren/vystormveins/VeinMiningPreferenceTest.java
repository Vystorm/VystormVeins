package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class VeinMiningPreferenceTest {
    private final Map<NamespacedKey, Byte> saved = new HashMap<>();

    private Player player() {
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(player.hasPermission("vystormveins.veinmine")).thenReturn(true);
        when(data.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.BYTE), anyByte()))
                .thenAnswer(i -> saved.getOrDefault(i.getArgument(0), i.getArgument(2)));
        doAnswer(i -> { saved.put(i.getArgument(0), i.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), anyByte());
        doAnswer(i -> { saved.remove(i.getArgument(0)); return null; }).when(data).remove(any(NamespacedKey.class));
        return player;
    }

    private VeinsPlugin plugin(boolean enabled) throws Exception {
        VeinsPlugin plugin = mock(VeinsPlugin.class, CALLS_REAL_METHODS);
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        YamlConfiguration config = new YamlConfiguration();
        config.set("vein-mining.enabled", enabled);
        var field = VeinsPlugin.class.getDeclaredField("veinMining");
        field.setAccessible(true);
        field.set(plugin, VeinMining.Options.read(config));
        return plugin;
    }

    private Command command() {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("veinmine");
        return command;
    }

    @Test void defaultsOnAndChoiceIsReadFromSavedPlayerDataInANewSession() {
        Player firstSession = player();
        assertTrue(VeinMining.playerEnabled(firstSession));
        assertFalse(VeinMining.toggle(firstSession));
        assertEquals(Map.of(new NamespacedKey("vystormveins", "vein-mining-disabled"), (byte) 1), saved);
        Player nextSession = player();
        assertFalse(VeinMining.playerEnabled(nextSession));
        assertTrue(VeinMining.toggle(nextSession));
        assertTrue(saved.isEmpty());
        assertTrue(VeinMining.playerEnabled(player()));
    }

    @Test void fallbackCommandTogglesWithoutAdminPermissionAndReportsBothStates() throws Exception {
        VeinsPlugin plugin = plugin(true);
        Player player = player();
        plugin.onCommand(player, command(), "veinmine", new String[0]);
        assertFalse(VeinMining.playerEnabled(player));
        verify(player).sendMessage("[VystormVeins] veinmine-disabled");
        plugin.onCommand(player, command(), "veinmine", new String[0]);
        assertTrue(VeinMining.playerEnabled(player));
        verify(player).sendMessage("[VystormVeins] veinmine-enabled");
    }

    @Test void serverDisabledStillSavesPreferenceAndExplainsGlobalRestriction() throws Exception {
        VeinsPlugin plugin = plugin(false);
        Player player = player();
        plugin.toggleVeinMining(player);
        assertFalse(VeinMining.playerEnabled(player));
        verify(player).sendMessage("[VystormVeins] veinmine-server-off");
        assertFalse(plugin.veinMining().enabled());
    }

    @Test void surplusArgumentsDoNotToggle() throws Exception {
        VeinsPlugin plugin = plugin(true);
        Player player = player();
        plugin.onCommand(player, command(), "veinmine", new String[]{"typo"});
        assertTrue(saved.isEmpty());
        verify(player).sendMessage("[VystormVeins] veinmine-usage");
        verify(player, never()).getPersistentDataContainer();
        assertTrue(plugin.onTabComplete(player, command(), "veinmine", new String[]{""}).isEmpty());
    }

    @Test void deniedPlayersAndConsoleCannotChangePreference() throws Exception {
        VeinsPlugin plugin = plugin(true);
        Player player = player();
        when(player.hasPermission("vystormveins.veinmine")).thenReturn(false);
        plugin.onCommand(player, command(), "veinmine", new String[0]);
        verify(player).sendMessage("[VystormVeins] denied");
        verify(player, never()).getPersistentDataContainer();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("vystormveins.veinmine")).thenReturn(true);
        plugin.onCommand(console, command(), "veinmine", new String[0]);
        verify(console).sendMessage("[VystormVeins] player-only");
        assertTrue(saved.isEmpty());
    }

    @Test void optedOutPlayerDoesNotScanOrScheduleExtraBlocks() throws Exception {
        VeinsPlugin plugin = plugin(true);
        Player player = player();
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isSneaking()).thenReturn(true);
        VeinMining.toggle(player);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        // called the way Vystorm Core's stage dispatcher calls the handler
        Fired.withCore(plugin, new VeinMiner(plugin)::handleBlockEvents).fire(event);
        verify(event, atLeastOnce()).getPlayer();
        verify(event, never()).getBlock();
        verify(plugin, never()).getServer();
    }

    @Test void queuedMiningRechecksPreferencePermissionAndServerSwitchBeforeBreakingBlocks() throws Exception {
        var mine = VeinMiner.class.getDeclaredMethod("mine", Player.class, org.bukkit.World.class, String.class,
                java.util.Set.class, java.util.List.class, VeinMining.Options.class, org.bukkit.Location.class);
        mine.setAccessible(true);
        VeinsPlugin plugin = plugin(true);
        VeinMining.Options scheduledOptions = plugin.veinMining();
        Player player = player();
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        VeinMiner miner = new VeinMiner(plugin);
        var vein = java.util.List.of(new int[]{1, 10, 0});
        VeinMining.toggle(player);
        mine.invoke(miner, player, world, "iron", java.util.Set.of(), vein, scheduledOptions, null);
        VeinMining.toggle(player);
        when(player.hasPermission("vystormveins.veinmine")).thenReturn(false);
        mine.invoke(miner, player, world, "iron", java.util.Set.of(), vein, scheduledOptions, null);
        when(player.hasPermission("vystormveins.veinmine")).thenReturn(true);
        doReturn(VeinMining.Options.DEFAULTS).when(plugin).veinMining();
        mine.invoke(miner, player, world, "iron", java.util.Set.of(), vein, scheduledOptions, null);
        verifyNoInteractions(world);
        verify(player, never()).breakBlock(any());
    }
}

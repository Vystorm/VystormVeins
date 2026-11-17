package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class VeinsCommandTest {
    @Test void worldChangeInvalidatesOnlyTheTravellingPlayersPreview() throws Exception {
        VeinsPlugin plugin = mock(VeinsPlugin.class, CALLS_REAL_METHODS);
        var player = mock(org.bukkit.entity.Player.class);
        var id = java.util.UUID.randomUUID(); var other = java.util.UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        var previews = new java.util.HashMap<java.util.UUID,Object>(); previews.put(id, new Object()); previews.put(other, new Object());
        var field = VeinsPlugin.class.getDeclaredField("previews"); field.setAccessible(true); field.set(plugin, previews);
        plugin.changedWorld(new org.bukkit.event.player.PlayerChangedWorldEvent(player, mock(org.bukkit.World.class)));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(other), previews.keySet());
        verify(player, never()).getWorld();
    }

    @Test void cancelIsPlayerOnlyAndDoesNotAccessWorldState() {
        VeinsPlugin plugin=mock(VeinsPlugin.class,CALLS_REAL_METHODS);
        CommandSender sender=mock(CommandSender.class);
        when(sender.hasPermission("vystormveins.admin")).thenReturn(true);
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.player-only","player only");
        doReturn(config).when(plugin).getConfig();
        assertTrue(plugin.onCommand(sender,null,"vveins",new String[]{"cancel"}));
        verify(sender).sendMessage("[VystormVeins] player only");
        verify(plugin,never()).getServer();
    }

    @Test void surplusApplyArgumentsShowUsageWithoutAccessingWorldState() {
        VeinsPlugin plugin=mock(VeinsPlugin.class,CALLS_REAL_METHODS);
        CommandSender sender=mock(CommandSender.class);
        when(sender.hasPermission("vystormveins.admin")).thenReturn(true);
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.usage","usage");
        doReturn(config).when(plugin).getConfig();

        assertTrue(plugin.onCommand(sender,null,"vveins",new String[]{"apply","typo"}));

        verify(sender).sendMessage("[VystormVeins] usage");
        verify(plugin,never()).getServer();
    }

    private static VeinsPlugin plugin(YamlConfiguration config) {
        VeinsPlugin plugin=mock(VeinsPlugin.class,CALLS_REAL_METHODS);
        doReturn(config).when(plugin).getConfig();
        return plugin;
    }

    private static org.bukkit.entity.Player admin(java.util.UUID id) {
        var player=mock(org.bukkit.entity.Player.class);
        when(player.hasPermission("vystormveins.admin")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static void previews(VeinsPlugin plugin, java.util.Map<java.util.UUID,Object> previews) throws Exception {
        var field=VeinsPlugin.class.getDeclaredField("previews"); field.setAccessible(true); field.set(plugin,previews);
    }

    @Test void invalidReloadKeepsPreviousSettingsAndShowsTheReason(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        java.nio.file.Files.writeString(folder.resolve("config.yml"),"worlds: [world]\n");
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.reload-failed","rejected: {0}");
        VeinsPlugin plugin=plugin(config);
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(java.util.logging.Logger.getAnonymousLogger()).when(plugin).getLogger();
        Settings previous=new Settings(java.util.Set.of("world"),java.util.Set.of(),java.util.List.of());
        var field=VeinsPlugin.class.getDeclaredField("settings"); field.setAccessible(true); field.set(plugin,previous);
        CommandSender sender=mock(CommandSender.class);
        when(sender.hasPermission("vystormveins.admin")).thenReturn(true);

        assertTrue(plugin.onCommand(sender,null,"vveins",new String[]{"reload"}));

        verify(sender).sendMessage("[VystormVeins] rejected: deposits missing");
        assertSame(previous,field.get(plugin));
        verify(plugin,never()).reloadConfig();
    }

    @Test void reloadReasonIsOneShortLine() {
        assertEquals("first line",VeinsPlugin.reason(new IllegalArgumentException("  first line \n second line")));
        assertEquals("NullPointerException",VeinsPlugin.reason(new NullPointerException()));
        assertEquals(160,VeinsPlugin.reason(new IllegalStateException("x".repeat(500))).length());
    }

    @Test void cancelWithoutPendingPreviewSaysSo() throws Exception {
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.no-preview","none");
        config.set("messages.cancelled","cancelled");
        VeinsPlugin plugin=plugin(config);
        var id=java.util.UUID.randomUUID(); var player=admin(id);
        var pending=new java.util.HashMap<java.util.UUID,Object>(); previews(plugin,pending);

        plugin.onCommand(player,null,"vveins",new String[]{"cancel"});
        verify(player).sendMessage("[VystormVeins] none");

        pending.put(id,new Object());
        plugin.onCommand(player,null,"vveins",new String[]{"cancel"});
        verify(player).sendMessage("[VystormVeins] cancelled");
        assertTrue(pending.isEmpty());
    }

    @Test void expiredPreviewIsReportedWithoutTouchingTheWorld() throws Exception {
        YamlConfiguration config=new YamlConfiguration();
        config.set("messages.preview-expired","expired");
        VeinsPlugin plugin=plugin(config);
        var id=java.util.UUID.randomUUID(); var player=admin(id);
        Class<?> type=Class.forName("at.esoren.vystormveins.VeinsPlugin$Preview");
        var constructor=type.getDeclaredConstructor(java.util.UUID.class,int.class,int.class,Settings.class,long.class);
        constructor.setAccessible(true);
        var pending=new java.util.HashMap<java.util.UUID,Object>();
        pending.put(id,constructor.newInstance(java.util.UUID.randomUUID(),0,0,null,System.nanoTime()-1));
        previews(plugin,pending);

        assertTrue(plugin.onCommand(player,null,"vveins",new String[]{"apply"}));

        verify(player).sendMessage("[VystormVeins] expired");
        verify(player,never()).getWorld();
        assertTrue(pending.isEmpty());
    }

    @Test void statusReportsClampedClaimSettings() {
        YamlConfiguration config=new YamlConfiguration();
        VeinsPlugin plugin=plugin(config);
        assertEquals(20, plugin.claimMinutes());
        assertEquals(128, plugin.claimMaxBlocks());
        config.set("claims.minutes", 9999); config.set("claims.max-blocks", 0);
        assertEquals(240, plugin.claimMinutes());
        assertEquals(1, plugin.claimMaxBlocks());
        DeepVeins.Options deep = DeepVeins.Options.read(config);
        assertTrue(deep.enabled());
        assertEquals(3, deep.players());
        assertEquals(12, deep.radius());
        assertEquals(-40, deep.maxY());
    }
}

package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.panel.*;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class OreMapPanelTest {
    private final OreMapPanel.Actions actions = mock(OreMapPanel.Actions.class);

    private static OreMapPanel.Texts texts(String language) {
        var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                OreMapPanelTest.class.getResourceAsStream("/lang/" + language + ".yml")), StandardCharsets.UTF_8));
        return (key, args) -> MiniMessage.miniMessage().deserialize(VeinsTexts.fill(Objects.requireNonNull(yaml.getString(key), key), args));
    }

    private static OreMapPanel.Overview overview(String selected, String filter) {
        return new OreMapPanel.Overview(List.of(
                new OreMapPanel.Row("0", Component.text("Chunk -4/-2"), Component.text("Iron: 12"), Material.RAW_IRON, true, Map.of("Iron", 12)),
                new OreMapPanel.Row("22", Component.text("Chunk 0/0"), Component.text("You are here"), Material.DIAMOND, true, Map.of("Diamond", 3)),
                new OreMapPanel.Row("44", Component.text("Chunk 4/2"), Component.text("Not loaded"), Material.BLACK_STAINED_GLASS_PANE, false, Map.of())),
                Map.of("iron", "Iron", "diamond", "Diamond"), filter, selected, -32, -16, 30);
    }

    private JsonObject render(String selected, String filter) {
        return PanelTesting.render(OreMapPanel.panel(overview(selected, filter), texts("en"), actions));
    }

    @Test void givenScan_whenRendered_thenSelectionDetailsFilterAndFooterArePresent() {
        JsonObject doc = render("22", null);
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("chunks", "detail", "chunk-status", "ore-counts", "direction", "filter", "refresh", "close")));
        assertEquals("22", PanelTesting.find(doc, "chunks").get("selected").getAsString());
        String detail = PanelTesting.find(doc, "detail").toString();
        assertTrue(detail.contains("Diamond") && detail.contains("3"), detail);
        assertFalse(detail.contains("Iron"), detail);
        assertTrue(doc.toString().contains("Counts ores from Y -32 to Y -16."), doc.toString());
        assertEquals("all", PanelTesting.find(doc, "filter").get("value").getAsString());
        assertEquals("Scan again", PanelTesting.plain(PanelTesting.find(doc, "refresh").get("label")));
    }

    @Test void givenUnloadedChunk_whenSelected_thenNoCountsAndDirectionIsDisabled() {
        JsonObject doc = render("44", "iron");
        assertNull(PanelTesting.find(doc, "ore-counts"));
        assertTrue(PanelTesting.find(doc, "direction").get("disabled").getAsBoolean());
        assertTrue(doc.toString().contains("That chunk is not loaded, so it was not scanned."));
        assertEquals("iron", PanelTesting.find(doc, "filter").get("value").getAsString());
    }

    @Test void givenMissingSelection_whenRendered_thenFirstChunkIsSelected() {
        assertEquals("0", PanelTesting.find(render("missing", null), "chunks").get("selected").getAsString());
    }

    @Test void givenEmptyScan_whenRendered_thenNoInvalidSelectionOrDirectionButton() {
        var empty = new OreMapPanel.Overview(List.of(), Map.of(), null, null, 0, 16, 30);
        JsonObject doc = PanelTesting.render(OreMapPanel.panel(empty, texts("en"), actions));
        assertNull(PanelTesting.find(doc, "direction"));
        assertFalse(PanelTesting.find(doc, "chunks").has("selected"));
    }

    @Test void givenAllLanguages_whenRendered_thenTranslatedLabelsHaveNoUnfilledKeys() {
        for (String language : List.of("de", "en", "fr", "it", "pt_br", "ru", "zh_cn")) {
            var labels = texts(language);
            JsonObject doc = PanelTesting.render(OreMapPanel.panel(overview("22", null), labels, actions));
            assertFalse(doc.toString().contains("map-panel-"), language);
            assertFalse(doc.toString().contains("{0}"), language);
            assertNotNull(PanelTesting.find(doc, "refresh"), language);
        }
    }

    @Test void givenFullScan_whenRendered_thenAllFortyFiveChunksPassClientSchema() {
        List<OreMapPanel.Row> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) rows.add(new OreMapPanel.Row(Integer.toString(i), Component.text("Chunk " + i),
                Component.text("Iron: 12"), Material.RAW_IRON, true, Map.of("Iron", 12)));
        var scan = new OreMapPanel.Overview(rows, Map.of("iron", "Iron"), null, "22", -64, 0, 30);
        JsonObject doc = PanelTesting.render(OreMapPanel.panel(scan, texts("en"), actions));
        assertEquals(45, PanelTesting.find(doc, "chunks").getAsJsonArray("items").size());
    }

    /** Obtain public callback interfaces from the builder without running a server or Core internals. */
    private static Object element(Object node, String id) throws Exception {
        if (node == null) return null;
        if (node instanceof Ui.Element<?>) {
            var field = Ui.Element.class.getDeclaredField("id"); field.setAccessible(true);
            if (id.equals(field.get(node))) return node;
        }
        for (Class<?> type = node.getClass(); type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                for (Object child : (List<?>) field.get(node)) {
                    if (!(child instanceof Ui.Element<?>)) continue;
                    Object found = element(child, id);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private Object handler(Panel panel, String id) throws Exception {
        Object target = element(panel, id);
        assertNotNull(target, id);
        var field = Ui.Element.class.getDeclaredField("handler"); field.setAccessible(true);
        return field.get(target);
    }

    @Test @SuppressWarnings("unchecked")
    void givenSelectionAction_whenAccepted_thenRefreshButRejectedChangesDoNotRefresh() throws Exception {
        var panel = OreMapPanel.panel(overview("22", null), texts("en"), actions);
        var action = mock(PanelAction.class); var player = mock(Player.class);
        when(action.player()).thenReturn(player);
        when(actions.select(player, "0")).thenReturn(Result.ok());
        var select = (Ui.Change<String>) handler(panel, "chunks");
        assertTrue(select.handle(action, "0").success());
        verify(action).refresh();
        clearInvocations(action);
        when(actions.select(player, "0")).thenReturn(Result.error("denied"));
        assertFalse(select.handle(action, "0").success());
        verify(action, never()).refresh();
    }

    @Test void givenRescanAction_whenAllowed_thenClosesBeforeHandingOver() throws Exception {
        var panel = OreMapPanel.panel(overview("22", null), texts("en"), actions);
        var action = mock(PanelAction.class); var player = mock(Player.class);
        when(action.player()).thenReturn(player);
        when(actions.check(player)).thenReturn(Result.error("denied"));
        Ui.Click refresh = (Ui.Click) handler(panel, "refresh");
        assertFalse(refresh.handle(action).success());
        verify(action, never()).closeAndThen(any());
        when(actions.check(player)).thenReturn(Result.ok());
        assertTrue(refresh.handle(action).success());
        verify(action).closeAndThen(any());
        verify(actions, never()).rescan(any());
    }

    private static VeinsPlugin plugin() {
        VeinsPlugin plugin = mock(VeinsPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.oreMap()).thenReturn(new OreMap.Options(true, 8, 30000));
        when(plugin.word(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(plugin.oreName(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        VeinsTexts texts = mock(VeinsTexts.class);
        when(plugin.texts()).thenReturn(texts);
        when(texts.ref(anyString())).thenAnswer(call -> call.getArgument(0));
        when(texts.component(any(), anyString(), any(Object[].class)))
                .thenAnswer(call -> Component.text((String) call.getArgument(1)));
        return plugin;
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("vystormveins.map")).thenReturn(true);
        return player;
    }

    private static OreMapMenu.View view() {
        return new OreMapMenu.View(List.of(new OreMap.Tile(0, 0, true, Map.of("iron", 12))),
                UUID.randomUUID(), 0, 0, -32, -16);
    }

    @Test void givenRegisteredPanel_whenFallbackRuns_thenReusesScanAndReportsUnavailableOpen() {
        VeinsPlugin plugin = plugin(); OreMapMenu menu = mock(OreMapMenu.class); Panels panels = mock(Panels.class);
        Player player = player(); OreMapMenu.View view = view();
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            var panel = new OreMapPanel(plugin, menu, PanelsInterface.DEFAULT);
            var capture = org.mockito.ArgumentCaptor.forClass(PanelSpec.class);
            verify(panels).register(eq(OreMapPanel.NAME), capture.capture());
            PanelSpec spec = capture.getValue();
            assertTrue(spec.access().test(player));
            when(panels.open(player, OreMapPanel.NAME)).thenReturn(PanelOpenResult.NONE);
            assertFalse(panel.open(player, view));
            spec.fallback().accept(player);
            verify(menu).showChest(player, view);
            verify(menu, never()).openChest(any());
            panel.forget(player.getUniqueId());
            spec.fallback().accept(player);
            verify(menu).openChest(player);
            when(player.hasPermission("vystormveins.map")).thenReturn(false);
            assertFalse(spec.access().test(player));
        }
    }

    @Test @SuppressWarnings("unchecked")
    void givenLiveHandlers_whenPermissionOrScanChanges_thenRejectStaleActions() throws Exception {
        VeinsPlugin plugin = plugin(); OreMapMenu menu = mock(OreMapMenu.class); Panels panels = mock(Panels.class);
        Player player = player(); OreMapMenu.View view = view();
        when(menu.allowed(player, view)).thenReturn(true);
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            var panel = new OreMapPanel(plugin, menu, PanelsInterface.DEFAULT);
            when(panels.open(player, OreMapPanel.NAME)).thenReturn(PanelOpenResult.NATIVE);
            assertTrue(panel.open(player, view));
            PanelContext ctx = mock(PanelContext.class); when(ctx.player()).thenReturn(player);
            Panel rendered = panel.render(ctx);
            var action = mock(PanelAction.class); when(action.player()).thenReturn(player);
            var filter = (Ui.Change<String>) handler(rendered, "filter");
            var select = (Ui.Change<String>) handler(rendered, "chunks");
            var describe = (Ui.Click) handler(rendered, "direction");
            assertFalse(filter.handle(action, "diamond").success(), "Unscanned ores cannot be selected");
            assertFalse(select.handle(action, "999").success());
            assertTrue(filter.handle(action, "iron").success());
            assertEquals("iron", view.filter);
            assertTrue(describe.handle(action).success());
            verify(menu).describe(player, view, 0);
            when(player.hasPermission("vystormveins.map")).thenReturn(false);
            assertFalse(filter.handle(action, "all").success());
            assertFalse(select.handle(action, "0").success());
            assertFalse(describe.handle(action).success());
            assertEquals("iron", view.filter);
            when(player.hasPermission("vystormveins.map")).thenReturn(true);
            panel.clear();
            assertFalse(filter.handle(action, "all").success());
            assertFalse(select.handle(action, "0").success());
            assertFalse(describe.handle(action).success());
            Ui.Click refresh = (Ui.Click) handler(rendered, "refresh");
            assertTrue(refresh.handle(action).success(), "An expired scan must still allow rescanning");
            verify(action).closeAndThen(any());
        }
    }

    /** Vystorm Core is a soft dependency: the classes that are always loaded must link without any Core class. */
    @Test void givenNoCoreAtAll_whenAlwaysLoadedTypesLink_thenNoLinkageFailure() throws Exception {
        var urls = new java.util.ArrayList<java.net.URL>();
        urls.add(OreMapMenu.class.getProtectionDomain().getCodeSource().getLocation());
        for (String path : System.getProperty("veins.testLibraries").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            urls.add(new java.io.File(path).toURI().toURL());
        try (var isolated = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("Vystorm.vystorm_core.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        }) {
            for (String name : List.of("VeinsPlugin", "VeinsTexts")) {
                // initialize = true links the class, which verifies every method body
                Class<?> type = Class.forName("at.esoren.vystormveins." + name, true, isolated);
                assertNotNull(type.getDeclaredMethods());
                assertNotNull(type.getDeclaredConstructors());
                assertNotNull(type.getDeclaredFields());
            }
        }
    }
}

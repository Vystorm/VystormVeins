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
import org.junit.jupiter.api.Test;

/** "My mining" in the ore map panel (1.24.0). */
class OreMapMineTest {
    private static final String[] LANGUAGES = {"de", "en", "fr", "it", "pt_br", "ru", "zh_cn"};
    private final OreMapPanel.Actions actions = mock(OreMapPanel.Actions.class);

    private static OreMapPanel.Texts texts(String language) {
        var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                OreMapMineTest.class.getResourceAsStream("/lang/" + language + ".yml")), StandardCharsets.UTF_8));
        return (key, args) -> MiniMessage.miniMessage().deserialize(VeinsTexts.fill(Objects.requireNonNull(yaml.getString(key), key), args));
    }

    private static MyMining.Journal journal() {
        return new MyMining.Journal(70, 2, 10, 0, 4, List.of(new MyMining.Line("Iron", 64, 3, 256L), new MyMining.Line("Coal", 6, 1, 16L),
                new MyMining.Line("Diamond", 0, 0, 1L)));
    }

    private static OreMapPanel.Overview overview(MyMining mine) {
        return new OreMapPanel.Overview(List.of(
                new OreMapPanel.Row("22", Component.text("Chunk 0/0"), Component.text("You are here"), Material.DIAMOND, true, Map.of("Diamond", 3))),
                Map.of("diamond", "Diamond"), null, "22", -32, -16, 30, mine);
    }

    private JsonObject render(MyMining mine, String language) {
        return PanelTesting.render(OreMapPanel.panel(overview(mine), texts(language), actions));
    }

    @Test void givenEverything_whenRendered_thenMapAndMyMiningAreTabsWithSwitchJournalAndRelease() {
        JsonObject doc = render(new MyMining(true, true, journal(), true, new MyMining.Claim(12, "Iron", 5)), "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("views", "chunks", "detail", "my-mining", "vein-mining", "vein-mining-off",
                "journal-total", "journal", "claim-status", "release-claim", "refresh", "close")), doc.toString());
        assertTrue(PanelTesting.find(doc, "vein-mining").get("value").getAsBoolean());
        String text = doc.toString();
        assertTrue(text.contains("70 discoveries") && text.contains("64 discoveries") && text.contains("next goal: 256"), text);
        assertTrue(text.contains("12 block(s) of Iron, 5 more minute(s)"), text);
        JsonObject release = PanelTesting.find(doc, "release-claim");
        assertEquals("danger", release.get("style").getAsString());
        assertTrue(release.getAsJsonObject("confirm").toString().contains("12 block(s) of Iron"), release.toString());
        assertFalse(release.has("disabled") && release.get("disabled").getAsBoolean());
    }

    @Test void givenNoClaimAndServerOn_whenRendered_thenReleaseIsDisabledAndNoOffNote() {
        JsonObject doc = render(new MyMining(false, false, null, true, null), "en");
        assertTrue(PanelTesting.find(doc, "release-claim").get("disabled").getAsBoolean());
        assertNull(PanelTesting.find(doc, "vein-mining-off"));
        assertNull(PanelTesting.find(doc, "journal"));
        assertTrue(doc.toString().contains("You have no mining claim right now."));
    }

    @Test void givenNothingAllowedOrNoMine_whenRendered_thenTheMapHasNoTabs() {
        assertNull(PanelTesting.find(render(new MyMining(null, false, null, false, null), "en"), "views"));
        assertNull(PanelTesting.find(render(null, "en"), "views"));
        assertNotNull(PanelTesting.find(render(null, "en"), "chunks"));
    }

    @Test void givenSevenLanguages_whenRendered_thenNoKeysAndShortLabels() {
        var yamlKeys = List.of("mine-tab-map", "mine-tab", "mine-vein", "mine-release");
        for (String language : LANGUAGES) {
            JsonObject doc = render(new MyMining(true, true, journal(), true, new MyMining.Claim(3, "Gold", 2)), language);
            assertFalse(doc.toString().contains("mine-") || doc.toString().contains("{0}"), language + doc);
            var t = texts(language);
            for (String key : yamlKeys) {
                String label = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(t.text(key));
                assertTrue(label.length() <= 14, language + " " + key + " " + label);
            }
        }
    }
}

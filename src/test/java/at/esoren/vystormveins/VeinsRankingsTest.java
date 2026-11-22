package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The ranking veins:mined in Core's placeholders (Core 0.67.0); Core stays optional. */
class VeinsRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final Plugin VEINS = PlaceholderTesting.plugin("VystormVeins");
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes()), SAM = UUID.nameUUIDFromBytes("sam".getBytes()),
            KIM = UUID.nameUUIDFromBytes("kim".getBytes());

    /** Journals copied at the players' last join (all offline now): Sam 120 ore blocks, Alex 40, Kim 40. */
    private static MinedTotals fixture() {
        MinedTotals totals = new MinedTotals(state -> { });
        VeinsPlugin.noteJournal(totals, SAM, "Sam", journal(100, 20));
        VeinsPlugin.noteJournal(totals, ALEX, "Alex", journal(40, 0));
        VeinsPlugin.noteJournal(totals, KIM, "Kim", journal(0, 40));
        VeinsPlugin.noteJournal(totals, UUID.randomUUID(), "Never", journal(0, 0));
        return totals;
    }

    /** A journal with {@code coal} coal and {@code diamond} diamond blocks. */
    private static long[] journal(long coal, long diamond) {
        long[] counts = new long[MiningJournal.FAMILIES.size()];
        counts[MiningJournal.FAMILIES.indexOf("coal")] = coal;
        counts[MiningJournal.FAMILIES.indexOf("diamond")] = diamond;
        return counts;
    }

    private static Throwable declare(PlaceholderTesting placeholders, boolean coreHasRankings, boolean on, MinedTotals totals) {
        return CoreLink.rankings(coreHasRankings, () -> VeinsRankings.apply(VEINS, placeholders.placeholders(), on, () -> totals, key -> "ref:" + key));
    }

    @Test void givenStoredTotals_whenDeclared_thenTheMinersAreListedInOrder() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, true, fixture()));
        assertEquals(List.of("veins:mined"), placeholders.rankings().stream().map(PlaceholderRanking::id).toList());

        List<RankingSource.Row> rows = placeholders.top("veins:mined", 10);
        assertEquals(List.of("Sam", "Alex", "Kim"), rows.stream().map(RankingSource.Row::name).toList(), "ties by name; no ore, no row");
        assertEquals(List.of(SAM, ALEX, KIM), rows.stream().map(RankingSource.Row::player).toList(), "offline players are listed");
        assertEquals(List.of(120.0, 40.0, 40.0), rows.stream().map(RankingSource.Row::value).toList());

        assertEquals("Sam", placeholders.resolve(null, "%vystorm_veins_top_mined_1_name%"));
        assertEquals("120", placeholders.resolve(null, "%vystorm_veins_top_mined_1_value%"));
        assertEquals("Kim", placeholders.resolve(null, "{veins:top_mined:3:name}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_veins_top_mined_4_name%"));
        assertEquals("ref:rankings.mined.label", placeholders.ranking("veins:mined").orElseThrow().info().label());
        assertEquals("ref:rankings.mined.description", placeholders.ranking("veins:mined").orElseThrow().info().description());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTheJournal_whenItGrowsOrShrinks_thenTheCopyOnlyGrowsAndIsSavedOnlyWhenAsked() {
        List<MinedTotals.State> saved = new ArrayList<>();
        MinedTotals totals = new MinedTotals(saved::add);
        VeinsPlugin.noteJournal(totals, SAM, "Sam", journal(5, 0));
        VeinsPlugin.noteJournal(totals, SAM, "Sam", journal(6, 0));
        VeinsPlugin.noteJournal(totals, SAM, "Sammy", journal(1, 0));   // a journal reset by an admin does not lower it
        assertEquals(6, totals.mined(SAM));
        assertEquals("Sammy", totals.top(1).getFirst().name(), "the last known name");
        assertEquals(List.of(), saved, "no file write per block");
        assertTrue(totals.dirty());
        totals.saveIfDirty();
        totals.saveIfDirty();
        assertEquals(1, saved.size(), "written once, then only after a change");
        VeinsPlugin.noteJournal(totals, SAM, "Sammy", journal(6, 0));
        assertFalse(totals.dirty(), "the same total changes nothing");

        // the file round trip (Gson, as Core's versioned store writes it); a malformed player is skipped
        Gson gson = new Gson();
        MinedTotals.State state = gson.fromJson(gson.toJson(saved.getFirst()), MinedTotals.State.class);
        MinedTotals.Entry broken = new MinedTotals.Entry();
        broken.mined = 99;
        state.players.put("not-a-uuid", broken);
        state.players.put(KIM.toString(), null);
        MinedTotals read = MinedTotals.of(state, s -> { });
        assertEquals(6, read.mined(SAM));
        assertEquals(1, read.top(10).size());
        assertEquals(0, MinedTotals.of(null, s -> { }).top(10).size());
    }

    @Test void givenTheSwitchOff_whenDeclared_thenNothingIsOfferedAndATurnedOffRankingIsRemoved() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, false, fixture()));
        assertTrue(placeholders.rankings().isEmpty());
        assertNull(declare(placeholders, true, true, fixture()));
        assertEquals(1, placeholders.rankings().size());
        assertNull(declare(placeholders, true, false, fixture()));   // /vveins reload with rankings.enabled: false
        assertTrue(placeholders.rankings().isEmpty());

        assertTrue(Feature.Switches.read(null).on(Feature.RANKINGS), "on by default");
        assertTrue(Feature.NEW_KEYS.contains(Feature.RANKINGS), "added to an existing config.yml with its comment");
    }

    @Test void givenACoreWithoutRankings_whenApplied_thenNothingIsDeclaredAndNothingThrows() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(CoreLink.rankings(false, () -> { throw new AssertionError("an older Core must not be asked"); }));
        assertTrue(placeholders.rankings().isEmpty());
        assertInstanceOf(NoClassDefFoundError.class,
                CoreLink.rankings(true, () -> { throw new NoClassDefFoundError("RankingInfo"); }));
        PlaceholderTesting taken = PlaceholderTesting.detached();
        taken.register(PlaceholderTesting.plugin("SomeoneElse"), "veins");
        assertInstanceOf(IllegalStateException.class, declare(taken, true, true, fixture()));
        PlaceholderTesting empty = PlaceholderTesting.detached();
        assertNull(declare(empty, true, true, null));
        assertEquals(List.of(), empty.top("veins:mined", 10), "no totals: empty");
    }

    /**
     * Without Vystorm Core the plugin's own classes load and the journal hook does nothing: run in a class loader that
     * has Paper but not the Core jar.
     */
    @Test void givenNoCoreAtAll_whenTheJournalChanges_thenNothingIsTouchedAndNothingFails() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(VeinsPlugin.class.getProtectionDomain().getCodeSource().getLocation());
        for (String path : System.getProperty("veins.testLibraries").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if (!new File(path).getName().toLowerCase(Locale.ROOT).contains("vystorm_core")) urls.add(new File(path).toURI().toURL());
        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("Vystorm.vystorm_core.CoreApi"), "really without Core");
            Class<?> plugin = Class.forName("at.esoren.vystormveins.VeinsPlugin", true, isolated);
            Class<?> totals = Class.forName("at.esoren.vystormveins.MinedTotals", true, isolated);
            Class.forName("at.esoren.vystormveins.MiningJournal", true, isolated);
            assertDoesNotThrow(plugin::getDeclaredMethods, "no Core type in the plugin's own signatures");
            assertDoesNotThrow(totals::getDeclaredMethods);
            Method note = plugin.getDeclaredMethod("noteJournal", totals, UUID.class, String.class, long[].class);
            note.setAccessible(true);
            assertDoesNotThrow(() -> note.invoke(null, null, SAM, "Sam", journal(3, 0)), "no totals without Core: nothing happens");
        }
    }

    @Test void givenEveryLanguage_whenRead_thenTheRankingHasALabelAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("rankings.mined.label", "").isBlank(), code);
            assertFalse(lang.getString("rankings.mined.description", "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.label(), "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.description(), "").isBlank(), code);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(config.getBoolean("rankings.enabled", false), "on by default");
        assertEquals("0.64.0", CoreLink.MIN_CORE_VERSION, "the minimum Core version stays");
    }

    /** Only VeinsRankings may name Core's ranking types: on an older Core a class that does fails to load. */
    @Test void givenTheSources_whenRead_thenOnlyVeinsRankingsNamesCoresRankingTypes() throws Exception {
        List<String> offending = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("VeinsRankings.java")) continue;
                String text = Files.readString(file);
                for (String type : List.of("RankingInfo", "RankingSource", "PlaceholderRanking", "removeRanking"))
                    if (java.util.regex.Pattern.compile("\\b" + type + "\\b").matcher(text).find()) offending.add(file.getFileName() + ": " + type);
            }
        }
        assertEquals(List.of(), offending);
    }
}

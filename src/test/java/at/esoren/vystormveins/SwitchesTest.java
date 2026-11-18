package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core.commands.CommandSpec;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Every feature has a switch (1.22.0), also without Vystorm Core: off stops the feature and answers the one shared
 * message, on behaves as before, an old config.yml gains only the new keys (a missing section still means what it meant),
 * and the placeholders resolve and are empty while their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("journal_total", "journal_discovered", "journal_families",
            "journal_collection_tier", "journal_family", "journal_family_tier", "claim_family", "claim_blocks_left", "veins_tracked",
            "veins_renewable");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final Path OLD = Path.of("src/test/resources/config-1.21.yml");

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsThereAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            String parent = feature.path.substring(0, feature.path.lastIndexOf('.'));
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
        assertTrue(config.getBoolean(Feature.GENERATION.path));
        assertTrue(config.getBoolean(Feature.JOURNAL.path));
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            assertTrue(lang.getString("feature-needs", "").contains("{0}"), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
    }

    @Test void givenAConfigFrom1_21_whenRead_thenEveryFeatureKeepsItsState() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(OLD.toFile());
        Set<Feature> off = Feature.Switches.read(old).off();
        for (Feature feature : Feature.values())
            assertEquals(!old.getBoolean(feature.path, feature.defaultOn), off.contains(feature), feature.path);
        assertTrue(off.contains(Feature.REGROWTH), "regrowth stays off by default");
        assertFalse(off.contains(Feature.GENERATION), "a config without the new key keeps generating");
    }

    @Test void givenAConfigFrom1_21_whenTheNewKeysAreAdded_thenOnlyTheyComeWithTheirComments() throws Exception {
        YamlConfiguration old = new YamlConfiguration();
        old.loadFromString(Files.readString(OLD));
        old.set("vein-mining", null); // a config from before 1.5.0: no section means vein mining is off
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString(Files.readString(CONFIG));

        assertTrue(Feature.addMissingKeys(old, defaults));

        for (Feature feature : Feature.NEW_KEYS) assertTrue(old.isBoolean(feature.path), feature.path);
        assertFalse(old.contains("vein-mining", true), "a missing section is not added");
        assertFalse(Feature.Switches.read(old).on(Feature.VEIN_MINING), "so vein mining stays off");
        assertTrue(old.saveToString().contains("Chunk preview and apply"), "comments come along");
        assertFalse(Feature.addMissingKeys(old, defaults), "only once");
    }

    @Test void givenGenerationOff_whenTheGenerationFeaturesAreAsked_thenOffAndTheOthersStay() {
        Feature.Switches off = Feature.Switches.without(Feature.GENERATION);
        assertFalse(off.on(Feature.CHUNK_PREVIEW));
        YamlConfiguration replacing = new YamlConfiguration();
        replacing.set("replace_natural.iron", true);
        assertTrue(Feature.Switches.read(replacing).on(Feature.REPLACE_IRON));
        replacing.set("generation.enabled", false);
        assertFalse(Feature.Switches.read(replacing).on(Feature.REPLACE_IRON), "replacing vanilla ore needs the generation");
        assertTrue(off.on(Feature.PROSPECTING), "the rod works on any ore");
        assertTrue(off.on(Feature.CLAIMS));
        assertTrue(off.on(Feature.JOURNAL));
        assertFalse(Feature.Switches.without(Feature.VEIN_MINING).on(Feature.MINING_SAFETY), "mining safety needs vein mining");
        assertEquals(1, Arrays.stream(Feature.values()).filter(Feature::needsRestart).count());
    }

    // ------------------------------------------------------------------ commands and handlers

    @Test void givenASwitchOff_whenItsCommandRuns_thenTheSharedMessageOrTheNeededSwitchAnswers() throws Exception {
        VeinsPlugin noJournal = plugin(Feature.JOURNAL);
        Player player = player();
        noJournal.journal(player);
        verify(textsOf(noJournal)).say(player, "feature-off");
        VeinsPlugin noPreview = plugin(Feature.CHUNK_PREVIEW);
        Player admin = player();
        noPreview.preview(admin);
        noPreview.apply(admin);
        verify(textsOf(noPreview), times(2)).say(admin, "feature-off");
        VeinsPlugin noGeneration = plugin(Feature.GENERATION);
        Player other = player();
        noGeneration.preview(other);
        verify(textsOf(noGeneration)).say(other, "feature-needs", "generation.enabled");
        VeinsPlugin noClaims = plugin(Feature.CLAIMS);
        Player claimer = player();
        noClaims.claimCommand(claimer, false);
        verify(textsOf(noClaims)).say(claimer, "feature-off");
    }

    @Test void givenJournalOff_whenAnOreIsMined_thenNothingIsCounted() throws Exception {
        VeinsPlugin plugin = plugin(Feature.JOURNAL);
        Player player = player();
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.IRON_ORE);
        new MiningJournal(plugin).broken(new BlockBreakEvent(block, player));
        verify(player, never()).getGameMode();
        verify(block, never()).getChunk();
    }

    @Test void givenASwitchOff_whenTheCommandTreesAreAsked_thenItsCommandsAreHiddenAndReturnWhenItIsOnAgain() throws Exception {
        assertTrue(CoreLink.liveCommands(), "built against Core 0.65.0");
        VeinsPlugin plugin = plugin();
        Map<String, CommandSpec> trees = new HashMap<>();
        for (CommandSpec spec : VeinsCommands.specs(plugin)) trees.put(spec.name(), spec);
        for (CommandSpec spec : trees.values()) assertTrue(spec.visibleTo(null), spec.name());
        field(plugin, "switches", Feature.Switches.without(Feature.JOURNAL, Feature.CLAIMS, Feature.CHUNK_PREVIEW));
        assertFalse(trees.get("veinjournal").visibleTo(null));
        assertFalse(trees.get("veinclaim").visibleTo(null));
        assertFalse(trees.get("vveins").children().get("preview").visibleTo(null));
        assertTrue(trees.get("vveins").children().get("status").visibleTo(null), "status and reload stay");
        assertTrue(trees.get("veinmine").visibleTo(null));
        field(plugin, "switches", Feature.Switches.without());
        assertTrue(trees.get("veinjournal").visibleTo(null), "on again");
        assertTrue(trees.get("veinclaim").visibleTo(null));
        assertTrue(trees.get("vveins").children().get("preview").visibleTo(null));
    }

    @Test void givenACoreOlderThan0_65_whenTheCommandTreesAreBuilt_thenSwitchedOffCommandsAreHiddenAtBuildTime() throws Exception {
        Map<String, CommandSpec> on = new HashMap<>();
        for (CommandSpec spec : VeinsCommands.specs(plugin(), false)) on.put(spec.name(), spec);
        for (CommandSpec spec : on.values()) assertFalse(spec.hidden(), spec.name());
        Map<String, CommandSpec> off = new HashMap<>();
        for (CommandSpec spec : VeinsCommands.specs(plugin(Feature.JOURNAL, Feature.CLAIMS, Feature.CHUNK_PREVIEW), false)) off.put(spec.name(), spec);
        assertTrue(off.get("veinjournal").hidden());
        assertTrue(off.get("veinclaim").hidden());
        assertTrue(off.get("vveins").children().get("preview").hidden());
        assertFalse(off.get("vveins").children().get("status").hidden(), "status and reload stay");
        assertFalse(off.get("veinmine").hidden());
    }

    @Test void givenASwitchChangedByAReload_whenTheSwitchesApply_thenCommandListsAreRefreshedWithoutAdoptingAgain() throws Exception {
        VeinsPlugin plugin = plugin(Feature.JOURNAL);
        Vystorm.vystorm_core.API api = mock(Vystorm.vystorm_core.API.class);
        Vystorm.vystorm_core._uiMethods.commands.CommandInterface commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(api.command()).thenReturn(commands);
        field(plugin, "core", new CoreLink(api));
        field(plugin, "commandSwitches", Feature.Switches.without());
        java.lang.reflect.Method apply = VeinsPlugin.class.getDeclaredMethod("applySwitches");
        apply.setAccessible(true);
        apply.invoke(plugin);
        verify(commands, times(1)).refresh();
        apply.invoke(plugin);
        verify(commands, times(1)).refresh();
        verify(commands, never()).adopt(any(), any(), any());
        verify(commands, never()).register(any(), any(), any());
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenTheNamespace_whenResolved_thenValuesAndEmptyWhileOff() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        Player viewer = player(); // the journal lives on the online player
        long[] counts = new long[MiningJournal.FAMILIES.size()];
        counts[MiningJournal.FAMILIES.indexOf("diamond")] = 20;
        counts[MiningJournal.FAMILIES.indexOf("iron")] = 5;
        VeinsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormVeins"), new VeinsPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature); }
            @Override public long[] journal(Player player) { return counts; }
            @Override public Optional<MiningClaims.Claim> claim(UUID player) {
                return Optional.of(new MiningClaims.Claim(player, "Steve", UUID.randomUUID(), "iron", 0L, Set.of(1L, 2L, 3L)));
            }
            @Override public int[] regrowth() { return new int[] {4, 3, 100}; }
        }, key -> key);

        assertEquals("25", testing.resolve(viewer, "%vystorm_veins_journal_total%"));
        assertEquals("2", testing.resolve(viewer, "{veins:journal_discovered}"));
        assertEquals("20", testing.resolve(viewer, "%vystorm_veins_journal_diamond%"));
        assertEquals(String.valueOf(MiningJournal.tier(20)), testing.resolve(viewer, "%vystorm_veins_journal_diamond_tier%"));
        assertEquals("10", testing.resolve(null, "%vystorm_veins_journal_families%"));
        assertEquals("iron", testing.resolve(viewer, "%vystorm_veins_claim_family%"));
        assertEquals("3", testing.resolve(viewer, "%vystorm_veins_claim_blocks_left%"));
        assertEquals("4", testing.resolve(null, "%vystorm_veins_veins_tracked%"));

        off.addAll(List.of(Feature.JOURNAL, Feature.CLAIMS));
        assertEquals("", testing.resolve(viewer, "%vystorm_veins_journal_total%"));
        assertEquals("", testing.resolve(viewer, "%vystorm_veins_claim_family%"));
    }

    // ------------------------------------------------------------------ helpers

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        return player;
    }

    /** A plugin object without a server: texts and the switches set, real methods run. */
    private static VeinsPlugin plugin(Feature... off) throws Exception {
        VeinsPlugin plugin = mock(VeinsPlugin.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        field(plugin, "texts", mock(VeinsTexts.class));
        field(plugin, "switches", Feature.Switches.without(off));
        return plugin;
    }

    private static VeinsTexts textsOf(VeinsPlugin plugin) throws Exception {
        Field field = VeinsPlugin.class.getDeclaredField("texts");
        field.setAccessible(true);
        return (VeinsTexts) field.get(plugin);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = VeinsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}

package at.esoren.vystormveins;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Every feature of the plugin with its on/off key in config.yml; a missing key keeps the value the plugin used before
 * the key existed, so an old config behaves as before. No Core types: the switches work without Vystorm Core, read
 * from config.yml and applied with /vveins reload; with Core they are also listed in /vadmin features with the
 * description {@code switches.<id>} ({@link #description()}). Keys that existed before 1.22.0 keep their names.
 */
enum Feature {
    /** Master switch of the generation: no veins in new chunks, no vanilla ore removed, no chunk preview or apply. */
    GENERATION("generation.enabled", true, null),
    REPLACE_COAL("replace_natural.coal", false, GENERATION),
    REPLACE_IRON("replace_natural.iron", false, GENERATION),
    REPLACE_COPPER("replace_natural.copper", false, GENERATION),
    REPLACE_GOLD("replace_natural.gold", false, GENERATION),
    REPLACE_REDSTONE("replace_natural.redstone", false, GENERATION),
    REPLACE_LAPIS("replace_natural.lapis", false, GENERATION),
    REPLACE_DIAMOND("replace_natural.diamond", false, GENERATION),
    REPLACE_EMERALD("replace_natural.emerald", false, GENERATION),
    REPLACE_NETHER_GOLD("replace_natural.nether_gold", false, GENERATION),
    REPLACE_NETHER_QUARTZ("replace_natural.nether_quartz", false, GENERATION),
    REPLACE_ANCIENT_DEBRIS("replace_natural.ancient_debris", false, GENERATION),
    CHUNK_PREVIEW("chunk-preview.enabled", true, GENERATION),
    PROSPECTING("prospecting.enabled", true, null),
    /** Missing in a config from before 1.5.0 means off (as before). */
    VEIN_MINING("vein-mining.enabled", false, null),
    /** Missing means off (opt-in for old installations, as before). */
    MINING_SAFETY("mining-safety.enabled", false, VEIN_MINING),
    /** Missing in a config from before 1.5.0 means off (as before); needs Vystorm Core. */
    ORE_MAP("ore-map.enabled", false, null),
    CLAIMS("claims.enabled", true, null),
    DEEP_VEINS("deep-veins.enabled", true, null),
    /** Off by default; needs Vystorm Core. */
    REGROWTH("regrowth.enabled", false, null),
    JOURNAL("mining-journal.enabled", true, null),
    /** Read once when the plugin starts; needs Vystorm Core. */
    TUTORIAL("tutorial.enabled", true, null);

    /** Keys that did not exist before 1.22.0; the plugin adds them to an existing config.yml with their comments. */
    static final List<Feature> NEW_KEYS = List.of(GENERATION, CHUNK_PREVIEW, JOURNAL, TUTORIAL);

    final String path;
    /** The value a missing key has (today's behaviour). */
    final boolean defaultOn;
    /** The switch this one needs, or null. */
    final Feature needs;

    Feature(String path, boolean defaultOn, Feature needs) {
        this.path = path;
        this.defaultOn = defaultOn;
        this.needs = needs;
    }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Language key of the readable name in {@code /vadmin features} and in "This needs ..." (1.22.1). */
    String label() { return "switch-names." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /**
     * Adds the keys of {@link #NEW_KEYS} that are missing in {@code config}, with the comments of {@code defaults} (of the
     * key, and of its section when the section is new). Nothing else is added: in this plugin a missing section can mean
     * "off" (vein mining, ore map from before 1.5.0), so Core's full merge of defaults stays off.
     *
     * @return whether keys were added (and the file should be saved)
     */
    static boolean addMissingKeys(YamlConfiguration config, YamlConfiguration defaults) {
        boolean changed = false;
        for (Feature feature : NEW_KEYS) {
            if (config.contains(feature.path, true)) continue;
            String section = feature.path.substring(0, feature.path.indexOf('.'));
            boolean newSection = !config.contains(section, true);
            config.set(feature.path, defaults.getBoolean(feature.path, feature.defaultOn));
            if (newSection) config.setComments(section, defaults.getComments(section));
            config.setComments(feature.path, defaults.getComments(feature.path));
            changed = true;
        }
        return changed;
    }

    /** The switches as read from a config; a missing key keeps its default. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            for (Feature feature : values()) {
                boolean on = config == null ? feature.defaultOn : config.getBoolean(feature.path, feature.defaultOn);
                if (!on) switches.off.add(feature);
            }
            return switches;
        }

        /** Every switch on, except the given ones. */
        static Switches without(Feature... off) {
            Switches switches = new Switches();
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch and the switch it needs are on. */
        boolean on(Feature feature) {
            if (off.contains(feature)) return false;
            return feature.needs == null || on(feature.needs);
        }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}

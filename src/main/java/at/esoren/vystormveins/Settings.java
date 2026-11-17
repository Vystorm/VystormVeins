package at.esoren.vystormveins;

import at.esoren.vystormveins.engine.VeinRule;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;

/** Validated configuration snapshot; swaps happen only after complete validation. */
public record Settings(Set<String> worlds, Set<String> replaceNatural, List<VeinRule> rules) {
    public Settings { worlds = Set.copyOf(worlds); replaceNatural = Set.copyOf(replaceNatural); rules = List.copyOf(rules); }
    /** Compatibility constructor for callers predating per-ore replacement. */
    public Settings(Set<String> worlds, boolean replaceVanilla, List<VeinRule> rules) {
        this(worlds, replaceVanilla ? NaturalReplacement.legacyAll() : Set.of(), rules);
    }
    public boolean replaceVanilla() { return !replaceNatural.isEmpty(); }
    public static Settings read(ConfigurationSection config) {
        List<VeinRule> rules = new ArrayList<>();
        ConfigurationSection section = Objects.requireNonNull(config.getConfigurationSection("deposits"), "deposits missing");
        if (section.getKeys(false).size() > 32) throw new IllegalArgumentException("Maximum 32 deposits");
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = Objects.requireNonNull(section.getConfigurationSection(id));
            String ore = material(s.getString("ore", ""));
            String deep = s.contains("deep-ore") ? material(s.getString("deep-ore", "")) : null;
            Set<String> hosts = new HashSet<>();
            for (String value : s.getStringList("hosts")) hosts.add(material(value));
            if (hosts.isEmpty()) throw new IllegalArgumentException(id + ": hosts must not be empty");
            Set<String> biomes = new HashSet<>();
            for (String value : s.getStringList("biomes")) {
                NamespacedKey key = NamespacedKey.fromString(value);
                if (key == null || Registry.BIOME.get(key) == null) throw new IllegalArgumentException("Unknown biome " + value);
                biomes.add(key.toString());
            }
            rules.add(new VeinRule(id, ore, deep, hosts, biomes, s.getInt("min-y", -64), s.getInt("max-y", 192),
                    s.getInt("attempts", 3), s.getInt("size", 32), s.getDouble("chance", 1)));
        }
        DeepVeins.addRule(config, rules, Settings::material);
        return new Settings(new HashSet<>(config.getStringList("worlds")), NaturalReplacement.read(config), rules);
    }
    private static String material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock() || material.isAir()) throw new IllegalArgumentException("Invalid block " + name);
        return material.getKey().toString();
    }
}

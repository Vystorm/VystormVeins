package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Independent selection of vanilla ore families; never changes vein rules. */
final class NaturalReplacement {
    private static final Map<String,Set<String>> FAMILIES;
    static {
        Map<String,Set<String>> families=new LinkedHashMap<>();
        for(String ore:List.of("coal","iron","copper","gold","redstone","lapis","diamond","emerald"))
            families.put(ore,Set.of("minecraft:"+ore+"_ore","minecraft:deepslate_"+ore+"_ore"));
        families.put("nether_gold",Set.of("minecraft:nether_gold_ore"));
        families.put("nether_quartz",Set.of("minecraft:nether_quartz_ore"));
        families.put("ancient_debris",Set.of("minecraft:ancient_debris"));
        FAMILIES=Collections.unmodifiableMap(families);
    }
    private NaturalReplacement() {}
    /** Names of the vanilla ore families ("coal", "nether_gold", ...). */
    static Set<String> familyNames() { return FAMILIES.keySet(); }
    static Set<String> legacyAll() {
        Set<String> result=new HashSet<>();
        FAMILIES.forEach((name,ores)->{if(!name.equals("ancient_debris"))result.addAll(ores);});
        return Set.copyOf(result);
    }
    static Set<String> read(ConfigurationSection config) {
        if(!config.contains("replace_natural",true)) return config.getBoolean("replace-vanilla",false)?legacyAll():Set.of();
        ConfigurationSection section=config.getConfigurationSection("replace_natural");
        if(section==null) throw new IllegalArgumentException("replace_natural must be a map of ore names to true/false");
        Set<String> result=new HashSet<>();
        for(String key:section.getKeys(false)) {
            if(!FAMILIES.containsKey(key)) throw new IllegalArgumentException("Unknown replace_natural ore: "+key);
            if(!(section.get(key) instanceof Boolean enabled)) throw new IllegalArgumentException("replace_natural."+key+" must be true/false");
            if(enabled)result.addAll(FAMILIES.get(key));
        }
        return Set.copyOf(result);
    }
}

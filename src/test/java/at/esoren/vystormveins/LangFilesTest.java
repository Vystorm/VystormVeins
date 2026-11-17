package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static YamlConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(LangFilesTest.class.getClassLoader().getResourceAsStream(name)),StandardCharsets.UTF_8));
    }

    @Test void englishAndGermanHaveTheSameKeysAndPlaceholders() { sameKeysAndPlaceholders("lang/de.yml"); }

    @Test void englishAndChineseHaveTheSameKeysAndPlaceholders() { sameKeysAndPlaceholders("lang/zh_cn.yml"); }

    private static void sameKeysAndPlaceholders(String other) {
        var en=load("lang/en.yml"); var de=load(other);
        assertEquals(en.getKeys(true),de.getKeys(true));
        Pattern placeholder=Pattern.compile("\\{\\d}");
        for(String key:en.getKeys(true)) {
            if(en.isConfigurationSection(key)) continue;
            Set<String> a=new TreeSet<>(), b=new TreeSet<>();
            placeholder.matcher(en.getString(key)).results().forEach(m->a.add(m.group()));
            placeholder.matcher(de.getString(key)).results().forEach(m->b.add(m.group()));
            assertEquals(a,b,other+" "+key);
        }
    }

    @Test void noRawArgumentTagsInTexts() {
        for(String file:List.of("lang/en.yml","lang/de.yml","lang/zh_cn.yml")) {
            var yaml=load(file);
            for(String key:yaml.getKeys(true))
                if(!yaml.isConfigurationSection(key)) assertFalse(yaml.getString(key).matches(".*<(arg|argument)[:>].*"),file+" "+key);
        }
    }

    @Test void everyNewTextKeyExists() {
        var en=load("lang/en.yml");
        for(String key:List.of("veinmine-enabled","veinmine-disabled","veinmine-server-off","veinmine-usage",
                "safety-stopped","safety-lava","safety-water","safety-falling","safety-unloaded","status-safety-on","status-safety-off",
                "veinmine-regrowth","veinmine-tool","status-veinmine","status-veinmine-off","status-sneak",
                "status-no-sneak","status-claims","status-claims-off","status-deep","status-deep-off","status-map","status-map-off","status-map-no-core","map-off","map-needs-core","map-cooldown",
                "map-menu-title","map-menu-description","map-title","map-hint","map-button","map-button-hover","map-tile","map-here",
                "map-empty","map-here-empty","map-unloaded","map-tile-line","map-tile-chunk","map-info","map-info-band",
                "map-info-north","map-info-click","map-refresh","map-refresh-hint","map-filter","map-filter-all","map-filter-hint",
                "map-close","map-none","map-chunk","map-chunk-here","map-chunk-unloaded"))
            assertTrue(en.contains(key),key);
    }
}

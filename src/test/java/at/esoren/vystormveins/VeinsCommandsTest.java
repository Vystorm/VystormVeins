package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** /vveins and /veinmap through Core's command trees with the real language files. */
class VeinsCommandsTest {
    private static final List<String> FILES = List.of("en", "de", "zh_cn");
    private final List<String> chat = new ArrayList<>();
    private final VeinsPlugin plugin = mock(VeinsPlugin.class);

    private static String read(String name) throws Exception {
        try (InputStream in = VeinsCommandsTest.class.getResourceAsStream("/lang/" + name + ".yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, CommandRegistration> trees() throws Exception {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormVeins");
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : FILES) files.put(f, read(f));
        Lang lang = Lang.create("VystormVeins", files);
        Map<String, CommandRegistration> out = new LinkedHashMap<>();
        for (CommandSpec spec : VeinsCommands.specs(plugin)) out.put(spec.name(), CommandTesting.detached(plugin, lang, spec));
        return out;
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    private ConsoleCommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        return console;
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = trees().get("vveins");
        String[] none = {""};
        assertEquals(List.of("status", "reload", "preview", "apply", "cancel", "help"), r.suggest(player(Locale.US, "vystormveins.admin"), "vveins", none));
        assertEquals(List.of("status", "neuladen", "vorschau", "anwenden", "verwerfen", "hilfe"), r.suggest(player(Locale.GERMANY, "vystormveins.admin"), "erzadern", none));
        assertEquals(List.of("状态", "重载", "预览", "应用", "取消", "帮助"), r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormveins.admin"), "矿脉", none));
        assertEquals(List.of(), r.suggest(player(Locale.US, "vystormveins.map"), "vveins", none), "admins only");
    }

    @Test
    void givenOldEnglishPaths_whenRun_thenSameHandlers() throws Exception {
        var trees = trees();
        CommandRegistration r = trees.get("vveins");
        Player admin = player(Locale.GERMANY, "vystormveins.admin", "vystormveins.map");
        r.execute(admin, "vveins", new String[0]);
        r.execute(admin, "vveins", new String[]{"status"});
        r.execute(admin, "vveins", new String[]{"STATUS"});
        verify(plugin, times(3)).status(admin);
        r.execute(admin, "vveins", new String[]{"reload"});
        verify(plugin).reloadCommand(admin);
        r.execute(admin, "vveins", new String[]{"preview"});
        verify(plugin).preview(admin);
        r.execute(admin, "vveins", new String[]{"apply"});
        verify(plugin).apply(admin);
        r.execute(admin, "vveins", new String[]{"cancel"});
        verify(plugin).cancel(admin);
        for (String label : List.of("veinmap", "oremap", "erzkarte")) trees.get("veinmap").execute(admin, label, new String[0]);
        verify(plugin, times(3)).veinMap(admin);
        assertEquals(List.of("oremap", "erzkarte"), trees.get("veinmap").fixedAliases());
    }

    @Test
    void givenLocalizedNames_whenRun_thenSameHandlers() throws Exception {
        CommandRegistration r = trees().get("vveins");
        Player english = player(Locale.US, "vystormveins.admin");
        r.execute(english, "erzadern", new String[]{"vorschau"});
        r.execute(english, "矿脉", new String[]{"预览"});
        verify(plugin, times(2)).preview(english);
        r.execute(english, "vveins", new String[]{"abbrechen"});
        verify(plugin).cancel(english);
    }

    @Test
    void givenConsole_whenStatusOrPlayerOnly_thenStatusRunsAndPreviewRefused() throws Exception {
        var trees = trees();
        ConsoleCommandSender console = console();
        trees.get("vveins").execute(console, "vveins", new String[]{"status"});
        trees.get("vveins").execute(console, "vveins", new String[]{"reload"});
        verify(plugin).status(console);
        verify(plugin).reloadCommand(console);
        trees.get("vveins").execute(console, "vveins", new String[]{"preview"});
        trees.get("veinmap").execute(console, "veinmap", new String[0]);
        verify(plugin, never()).preview(any());
        verify(plugin, never()).veinMap(any());
        assertEquals(List.of("Only players can do that.", "Only players can do that."), chat);
    }

    @Test
    void givenNoPermission_whenRun_thenRefused() throws Exception {
        trees().get("vveins").execute(player(Locale.GERMANY, "vystormveins.map"), "vveins", new String[]{"reload"});
        verify(plugin, never()).reloadCommand(any());
        assertEquals("Dafür hast du keine Berechtigung.", chat.get(0));
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        trees().get("vveins").execute(player(Locale.GERMANY, "vystormveins.admin"), "erzadern", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/erzadern vorschau – Vorschau: Erze deines Chunks neu erzeugen"), all);
        assertTrue(all.contains("/erzadern neuladen – config.yml prüfen"), all);
    }

    @Test
    void personalVeinMiningCommandUsesCorePermissionsAndRejectsSurplusArguments() throws Exception {
        CommandRegistration r = trees().get("veinmine");
        Player player = player(Locale.GERMANY, "vystormveins.veinmine");
        r.execute(player, "aderabbau", new String[0]);
        verify(plugin).toggleVeinMining(player);
        clearInvocations(plugin);
        r.execute(player, "veinmine", new String[]{"typo"});
        r.execute(player(Locale.US), "veinmine", new String[0]);
        r.execute(console(), "veinmine", new String[0]);
        verify(plugin, never()).toggleVeinMining(any());
    }

    @Test
    void givenTrees_whenChecked_thenNoNameConflicts() throws Exception {
        for (CommandRegistration r : trees().values()) assertEquals(List.of(), r.conflicts(), r.label());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndEveryNodeDescribed() throws Exception {
        Map<String, YamlConfiguration> yaml = new LinkedHashMap<>();
        for (String f : FILES) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(new StringReader(read(f)));
            yaml.put(f, y);
        }
        Set<String> english = new TreeSet<>(yaml.get("en").getConfigurationSection("commands").getKeys(true));
        for (String f : FILES) assertEquals(english, new TreeSet<>(yaml.get(f).getConfigurationSection("commands").getKeys(true)), f);
        List<CommandSpec> nodes = new ArrayList<>();
        for (CommandSpec root : VeinsCommands.specs(null)) collect(root, nodes);
        for (String f : FILES) for (CommandSpec node : nodes) {
            assertTrue(yaml.get(f).isString(node.langPath() + ".description"), f + " " + node.langPath());
            assertTrue(yaml.get(f).isList(node.langPath() + ".aliases"), f + " " + node.langPath());
            if (!node.isRoot()) assertTrue(yaml.get(f).isString(node.langPath() + ".name"), f + " " + node.langPath());
        }
    }

    private static void collect(CommandSpec node, List<CommandSpec> out) {
        out.add(node);
        for (CommandSpec child : node.children().values()) collect(child, out);
    }
}

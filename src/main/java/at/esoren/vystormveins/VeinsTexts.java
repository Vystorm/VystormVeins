package at.esoren.vystormveins;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Player-visible texts. With Vystorm Core they come from {@code lang/en.yml} and {@code de.yml} in the player's
 * language; without Core in English. Texts an admin changed in config.yml (older versions kept them
 * there) still win, unchanged old defaults do not.
 */
final class VeinsTexts {
    private final JavaPlugin plugin;
    private final YamlConfiguration legacy;
    private final YamlConfiguration bundledEnglish;
    /** The link to Core; null without a usable Core. */
    private final CoreLink core;
    private Object lang;

    /** @param core the link to Vystorm Core 0.64.0+, or null: the texts are then English */
    VeinsTexts(JavaPlugin plugin, CoreLink core) {
        this.core = core;
        this.plugin = plugin;
        this.legacy = resource(plugin, "legacy-texts.yml");
        this.bundledEnglish = resource(plugin, "lang/en.yml");
        if (core != null) {
            try { lang = core.registerLang(plugin); } catch (RuntimeException unavailable) { lang = null; }
        }
    }

    private static YamlConfiguration resource(JavaPlugin plugin, String name) {
        var stream = plugin.getResource(name);
        if (stream == null) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    void reload() { if (lang != null) CoreTexts.reload(lang); }

    String ref(String key) { return lang == null ? bundledEnglish.getString(key, key) : CoreTexts.ref(lang, key); }

    /** Config path of a text in versions up to 1.3 ("messages.x", "messages.directions.N", "ore-names.coal"). */
    static String legacyPath(String key) { return key.startsWith("ore-names.") ? key : "messages." + key; }

    /** An admin-changed text from config.yml, or null when the text is missing there or still the old default. */
    String custom(String key) {
        String path = legacyPath(key);
        String value = plugin.getConfig().getString(path);
        return value != null && !value.equals(legacy.getString(path)) ? value : null;
    }

    static String fill(String text, Object... args) {
        for (int i = 0; i < args.length; i++) text = text.replace("{" + i + "}", String.valueOf(args[i]));
        return text;
    }

    /** Short word for a viewer (directions, ore names), plain text. */
    String word(CommandSender viewer, String key) {
        String custom = custom(key);
        if (custom != null) return custom;
        if (lang != null) return CoreTexts.plain(lang, viewer, key);
        return bundledEnglish.getString(key, key);
    }

    /** Sends a chat line with the plugin prefix. */
    void say(CommandSender to, String key, Object... args) {
        Component message = Component.text("[VystormVeins] ", NamedTextColor.DARK_GRAY).append(component(to, key, args));
        if (lang != null) core.send(to, message);
        else to.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(message));
    }

    /** A formatted text without prefix for a viewer, e.g. menu item names and lore. */
    Component component(CommandSender viewer, String key, Object... args) {
        String custom = custom(key);
        if (custom != null || lang == null) {
            String text = custom != null ? custom : bundledEnglish.getString(key, key);
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(fill(text, args));
        }
        Object[] pairs = new Object[args.length * 2];
        for (int i = 0; i < args.length; i++) { pairs[2 * i] = Integer.toString(i); pairs[2 * i + 1] = args[i]; }
        return CoreTexts.component(lang, viewer, key, pairs);
    }

    /** Only class here that touches Core types; loaded only when Core is installed. */
    private static final class CoreTexts {
        static void reload(Object lang) { ((Vystorm.vystorm_core.lang.Lang) lang).reload(); }

        static String ref(Object lang, String key) { return ((Vystorm.vystorm_core.lang.Lang) lang).ref(key); }

        static String plain(Object lang, CommandSender viewer, String key) { return ((Vystorm.vystorm_core.lang.Lang) lang).plain(viewer, key); }

        static Component component(Object lang, CommandSender viewer, String key, Object[] pairs) {
            return CoreComponents.component(lang, viewer, key, pairs);
        }
    }
}

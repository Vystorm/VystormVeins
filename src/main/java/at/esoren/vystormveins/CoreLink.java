package at.esoren.vystormveins;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vystorm Core is optional. This class finds the Core hub and holds it for everything the plugin does with Core;
 * it is touched only while Core is enabled, so {@link VeinsPlugin} and {@link VeinsTexts} link without Core.
 * The Core-based features use Core's managers and therefore need Core {@value #MIN_CORE_VERSION}.
 */
final class CoreLink {
    static final String MIN_CORE_VERSION = "0.64.0";
    private final API core;

    CoreLink(API core) { this.core = core; }

    /**
     * The link to an enabled Core; {@code null}, with one log line, when the installed Core is older than
     * {@value #MIN_CORE_VERSION}: the Core-based features then stay off and the plugin keeps running.
     */
    static CoreLink connect(VeinsPlugin plugin) {
        if (!CoreApi.atLeast(MIN_CORE_VERSION)) {
            plugin.getLogger().warning("Vystorm Core " + CoreApi.version() + " is installed, but the Core features of VystormVeins "
                    + "(texts in the player's language, ore map, localized commands, regrowth, config menu, tutorial, party claims) "
                    + "need Vystorm Core " + MIN_CORE_VERSION + " or newer. They are switched off; generation, prospecting and "
                    + "vein mining keep working. Please update Core.");
            return null;
        }
        return new CoreLink(CoreApi.api().orElseThrow());
    }

    /**
     * Hands a handler to Core's stages, so Core orders it among all Vystorm plugins; skipped for cancelled events
     * unless {@code receiveCancelled} is set.
     */
    <E extends org.bukkit.event.Event> void handle(org.bukkit.plugin.Plugin plugin, Class<E> type, EventOrder.Stage stage,
            boolean receiveCancelled, java.util.function.Consumer<? super E> handler) {
        core.priority().stages().handle(plugin, type,
                Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.valueOf(stage.name()), receiveCancelled, handler);
    }

    /** Update check in /vadmin. */
    void registerUpdates(VeinsPlugin plugin) { core.admin().updates().spigot(plugin, 139880); }

    /** config.yml in Core's config menu; {@code reload} runs after a change. */
    void registerConfigMenu(VeinsPlugin plugin, Runnable reload) { core.settings().config().registerAll(plugin, reload, false); }

    void registerTutorial(VeinsPlugin plugin) { core.registerTutorial(plugin); }

    /** The plugin's language bundle, a Core {@code Lang}; typed as Object so that {@link VeinsTexts} links without Core. */
    Object registerLang(JavaPlugin plugin) { return core.lang().register(plugin); }

    /** Sends a component of the plugin's own Adventure library through Core. */
    void send(CommandSender sender, Component text) { CoreComponents.send(core, sender, text); }

    boolean sameParty(UUID a, UUID b) { return core.parties().sameParty(a, b); }

    OreMapMenu oreMapMenu(VeinsPlugin plugin) { return new OreMapMenu(plugin, core); }

    /** Hands the plugin.yml commands to Core's command trees. */
    void adoptCommands(VeinsPlugin plugin) { VeinsCommands.adopt(plugin, core.command()); }

    Regrowth regrowth(VeinsPlugin plugin) { return new Regrowth(plugin, core.data().files(), core.tasks()); }
}

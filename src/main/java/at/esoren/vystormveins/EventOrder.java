package at.esoren.vystormveins;

import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Registers an event handler by what it does instead of by a Bukkit priority.
 * <p>
 * With Vystorm Core the handler goes to Core's stages ({@code api.priority().stages()}), so Core decides when it runs
 * among all Vystorm plugins. Without Core it becomes an ordinary Bukkit listener at the priority Core dispatches that
 * stage at, so the plugin behaves the same on its own. Either way the handler is not called for a cancelled event,
 * unless it was registered to receive those too.
 * The handler method is the same in both cases; only the registration differs.
 */
final class EventOrder {
    /** What a handler does; the names are those of Core's {@code EventStage}. */
    enum Stage {
        /** Allows or denies the action (claims). */
        PROTECT(EventPriority.LOW),
        /** Changes the outcome or applies a rule of the plugin. */
        ADJUST(EventPriority.NORMAL),
        /** The feature acts on an allowed action. */
        ACT(EventPriority.HIGH),
        /** Last corrections after every feature has acted. */
        FINISH(EventPriority.HIGHEST),
        /** Reads the final outcome: bookkeeping, rewards, and work that starts after the action really happened. */
        OBSERVE(EventPriority.MONITOR);

        private final EventPriority bukkit;

        Stage(EventPriority bukkit) { this.bukkit = bukkit; }

        /** The Bukkit priority of this stage, with and without Core. */
        EventPriority bukkit() { return bukkit; }
    }

    private static final Listener LISTENER = new Listener() {};
    private final Plugin plugin;
    /** Null without a usable Core. */
    private final CoreLink core;

    EventOrder(Plugin plugin, CoreLink core) {
        this.plugin = plugin;
        this.core = core;
    }

    /** Whether the handlers are ordered by Core. */
    boolean ordered() { return core != null; }

    /** Registers a handler that is skipped for cancelled events. */
    <E extends Event> void handle(Class<E> type, Stage stage, Consumer<? super E> handler) {
        handle(type, stage, false, handler);
    }

    /**
     * Registers a handler.
     *
     * @param receiveCancelled true to be called for cancelled events too (a click into the air arrives cancelled)
     */
    <E extends Event> void handle(Class<E> type, Stage stage, boolean receiveCancelled, Consumer<? super E> handler) {
        if (core != null) {
            try {
                core.handle(plugin, type, stage, receiveCancelled, handler);
                return;
            } catch (RuntimeException | LinkageError unavailable) {
                plugin.getLogger().warning("Vystorm Core could not take the " + type.getSimpleName() + " handler (" + unavailable
                        + "); it runs as a plain listener.");
            }
        }
        plugin.getServer().getPluginManager().registerEvent(type, LISTENER, stage.bukkit(),
                (listener, event) -> { if (type.isInstance(event)) handler.accept(type.cast(event)); }, plugin, !receiveCancelled);
    }
}

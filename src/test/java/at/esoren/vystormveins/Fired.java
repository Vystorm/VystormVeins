package at.esoren.vystormveins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Fires events at handlers registered through an {@link EventOrder}, in both ways the plugin can run: with Vystorm
 * Core (its real stage dispatcher, see Core's {@link EventStageTesting}) and without Core (the plain Bukkit listeners, called in
 * priority order and skipped for cancelled events when registered so, as the server does).
 */
interface Fired {
    <E extends Event> E fire(E event);

    /** The ways the plugin registers its handlers; for tests that must hold in both. */
    enum Mode { WITH_CORE, WITHOUT_CORE }

    static Fired in(Mode mode, Plugin plugin, Consumer<EventOrder> registrations) {
        return mode == Mode.WITH_CORE ? withCore(plugin, registrations) : withoutCore(plugin, registrations);
    }

    /** Handlers ordered by Core: {@code registrations} get an EventOrder linked to Core's stage registry. */
    static Fired withCore(Plugin plugin, Consumer<EventOrder> registrations) {
        enabled(plugin);
        API api = mock(API.class);
        EventStageTesting stages = EventStageTesting.detached();
        when(api.priority()).thenReturn(stages.priority());
        registrations.accept(new EventOrder(plugin, new CoreLink(api)));
        return stages::fire;
    }

    /** Handlers as plain Bukkit listeners: {@code registrations} get an EventOrder without Core. */
    static Fired withoutCore(Plugin plugin, Consumer<EventOrder> registrations) {
        enabled(plugin);
        record Bound(EventExecutor executor, boolean ignoreCancelled) {}
        Map<EventPriority, List<Bound>> listeners = new EnumMap<>(EventPriority.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        doReturn(server).when(plugin).getServer();
        when(server.getPluginManager()).thenReturn(manager);
        doAnswer(call -> {
            EventPriority priority = call.getArgument(2);
            listeners.computeIfAbsent(priority, ignored -> new ArrayList<>()).add(new Bound(call.getArgument(3), call.getArgument(5)));
            return null;
        }).when(manager).registerEvent(any(), any(), any(), any(), any(), anyBoolean());
        registrations.accept(new EventOrder(plugin, null));
        Listener listener = mock(Listener.class);
        return new Fired() {
            @Override public <E extends Event> E fire(E event) {
                for (EventPriority priority : EventPriority.values()) {
                    for (Bound bound : listeners.getOrDefault(priority, List.of())) {
                        if (bound.ignoreCancelled() && event instanceof Cancellable cancellable && cancellable.isCancelled()) continue;
                        try {
                            bound.executor().execute(listener, event);
                        } catch (EventException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                }
                return event;
            }
        };
    }

    private static void enabled(Plugin plugin) {
        doReturn("VystormVeins").when(plugin).getName();
        doReturn(true).when(plugin).isEnabled();
    }
}

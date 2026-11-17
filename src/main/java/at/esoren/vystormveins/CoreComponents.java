package at.esoren.vystormveins;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.chat.ChatActionsInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;

/** Core is optional and Bukkit gives each plugin's libraries a separate class loader.
 * Only JSON strings cross that boundary; click/hover events and translatable arguments survive.
 * The calls go to the parts of Core's managers ({@code core.compat().platform()}, {@code core.message().chat()}),
 * by reflection because their component parameters are Core's Adventure classes, not this plugin's. */
final class CoreComponents {
    private CoreComponents() {}

    private static final class Holder {
        static final ClassLoader LOADER = Vystorm.vystorm_core.CoreApi.class.getClassLoader();
        static final Codec CODEC = new Codec(LOADER);
    }

    static Component component(Object lang, CommandSender viewer, String key, Object[] pairs) {
        return Holder.CODEC.local(invoke(method(lang.getClass(), "component", CommandSender.class,
                String.class, Object[].class), lang, viewer, key, pairs));
    }

    static void send(API core, CommandSender sender, Component value) {
        platform(core, "sendMessage", new Class<?>[]{CommandSender.class, Holder.CODEC.componentType},
                sender, Holder.CODEC.foreign(value));
    }

    static Component button(API core, String label, String hover, String permission, Consumer<Player> action) {
        return Holder.CODEC.local(invoke(method(ChatActionsInterface.class, "button", String.class, String.class,
                String.class, Consumer.class), core.message().chat(), label, hover, permission, action));
    }

    static void displayName(API core, ItemMeta meta, Component value) {
        platform(core, "displayName", new Class<?>[]{ItemMeta.class, Holder.CODEC.componentType},
                meta, Holder.CODEC.foreign(value));
    }

    static void lore(API core, ItemMeta meta, List<Component> value) {
        platform(core, "lore", new Class<?>[]{ItemMeta.class, List.class},
                meta, value.stream().map(Holder.CODEC::foreign).toList());
    }

    private static void platform(API core, String name, Class<?>[] parameters, Object... args) {
        invoke(method(PlatformCompatInterface.class, name, parameters), core.compat().platform(), args);
    }

    /** Package-visible for an isolated-library regression, without requiring a running Core plugin. */
    static final class Codec {
        final Class<?> componentType;
        private final Object serializer;
        private final Method serialize, deserialize;

        Codec(ClassLoader loader) {
            componentType = type(loader, "net.kyori.adventure.text.Component");
            Class<?> gson = type(loader, "net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            serializer = invoke(method(gson, "gson"), null);
            Class<?> contract = type(loader, "net.kyori.adventure.text.serializer.ComponentSerializer");
            serialize = method(contract, "serialize", componentType);
            deserialize = method(contract, "deserialize", Object.class);
        }

        Object foreign(Component value) {
            if (componentType.isInstance(value)) return value;
            return invoke(deserialize, serializer, GsonComponentSerializer.gson().serialize(value));
        }

        Component local(Object value) {
            if (value instanceof Component component) return component;
            if (!componentType.isInstance(value)) throw new IllegalArgumentException("Not a Core component");
            return GsonComponentSerializer.gson().deserialize((String) invoke(serialize, serializer, value));
        }
    }

    private static Class<?> type(ClassLoader loader, String name) {
        try { return Class.forName(name, true, loader); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("Missing Core text API: " + name, failure); }
    }
    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try { return owner.getMethod(name, parameters); }
        catch (NoSuchMethodException failure) { throw new IllegalStateException("Missing Core text method: " + name, failure); }
    }
    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Core text method failed", cause);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot invoke Core text API", failure); }
    }
}

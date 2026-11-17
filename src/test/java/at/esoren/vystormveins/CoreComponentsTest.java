package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class CoreComponentsTest {
    @Test void isolatedAdventureCopiesPreserveRichTextAndActions() throws Exception {
        URL[] libraries = Arrays.stream(System.getProperty("veins.testLibraries").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(path -> { try { return new File(path).toURI().toURL(); } catch (Exception failure) { throw new IllegalStateException(failure); } })
                .toArray(URL[]::new);
        try (URLClassLoader isolated = new URLClassLoader(libraries, ClassLoader.getPlatformClassLoader())) {
            CoreComponents.Codec codec = new CoreComponents.Codec(isolated);
            Component original = Component.translatable("veins.test", "Ore %s", Component.text("diamond"))
                    .color(TextColor.color(0x12abef)).clickEvent(ClickEvent.runCommand("/vcoreclick test-id"))
                    .hoverEvent(HoverEvent.showText(Component.text("Open ore map")))
                    .append(Component.text(" nested"));
            Object foreign = codec.foreign(original);
            assertFalse(foreign instanceof Component, "The regression must use genuinely separate Adventure classes");
            assertTrue(codec.componentType.isInstance(foreign));
            assertEquals(original, codec.local(foreign));
        }
    }

    @Test void sharedAdventureKeepsTheOriginalComponent() {
        CoreComponents.Codec codec = new CoreComponents.Codec(Component.class.getClassLoader());
        Component original = Component.text("shared Paper runtime");
        assertSame(original, codec.foreign(original));
        assertSame(original, codec.local(original));
    }
}

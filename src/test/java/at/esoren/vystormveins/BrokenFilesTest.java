package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml with a YAML error is never saved over; /vveins reload names line and column and keeps the settings. */
class BrokenFilesTest {
    static final String BROKEN = "deposits:\n  copper:\n    size: 8\n broken here\nworlds:\n  - world\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    @Test void givenBrokenConfig_whenReloaded_thenTheFileStaysAndTheAnswerNamesLineAndColumn() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        VeinsPlugin plugin = mock(VeinsPlugin.class, CALLS_REAL_METHODS);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("messages.reload-failed", "Configuration invalid, the previous settings stay active: {0}");
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger).when(plugin).getLogger();
        doReturn(messages).when(plugin).getConfig();
        doReturn(null).when(plugin).getResource("config.yml");
        doNothing().when(plugin).reloadConfig();
        doNothing().when(plugin).saveConfig();
        CommandSender admin = mock(CommandSender.class);
        plugin.reloadCommand(admin);
        verify(admin).sendMessage(argThat((String text) -> text.matches(".*stay active: line 4, column \\d+: .+")));
        verify(plugin, never()).reloadConfig();
        verify(plugin, never()).saveConfig();
        assertArrayEquals(before, Files.readAllBytes(file));
        List<LogRecord> severe = logged.stream().filter(r -> r.getLevel() == Level.SEVERE).toList();
        assertEquals(1, severe.size());
        assertTrue(severe.getFirst().getMessage().contains("line 4, column"), severe.getFirst().getMessage());
    }

    @Test void givenBrokenYaml_whenDescribed_thenLineColumnAndProblemAreNamed() throws Exception {
        Path file = folder.resolve("x.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertTrue(YamlCheck.problem(file.toFile()).matches("line 4, column \\d+: .+"), YamlCheck.problem(file.toFile()));
    }
}

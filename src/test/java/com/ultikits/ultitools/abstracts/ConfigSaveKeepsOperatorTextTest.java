package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #596: a value the framework could not convert, and a key the operator deleted, are the operator's. A save
 * (module {@code save()}, and until plan 17-65 task 3 the shutdown save) leaves them exactly as the operator left
 * them, also when the module changed that setting itself: a setting is written only when the file still holds the
 * value the module started from (17-65 save rule; maintainer decision 2026-10-04, "what code may write, by file
 * type", which supersedes #527's overwrite-and-warn). A change that is not written stays in memory and one warning
 * names the key, never a value.
 */
@DisplayName("Saves keep unconvertible and deleted keys as the operator left them (#596)")
class ConfigSaveKeepsOperatorTextTest {

    private static final String PATH = "keep.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;
    private Logger frameworkLogger;

    @ConfigEntity(PATH)
    public static class Cfg extends AbstractConfigEntity {
        @ConfigEntry(path = "item.interval")
        int interval = 300;

        @ConfigEntry(path = "item.warn-times")
        List<Integer> warnTimes = new ArrayList<>(Arrays.asList(60, 30, 10));

        @ConfigEntry(path = "item.message")
        String message = "hello";

        public Cfg(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        frameworkLogger = Mockito.mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> Mockito.when(ultiTools.getLogger()).thenReturn(frameworkLogger));
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("KeepModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
    }

    private void put(String text) throws Exception {
        Files.write(file(), text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> unwrittenWarnings() {
        ArgumentCaptor<Level> levels = ArgumentCaptor.forClass(Level.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        Mockito.verify(frameworkLogger, Mockito.atLeast(0)).log(levels.capture(), messages.capture());
        List<String> found = new ArrayList<>();
        for (int i = 0; i < messages.getAllValues().size(); i++) {
            if (levels.getAllValues().get(i) == Level.WARNING && messages.getAllValues().get(i).contains("were not written")) {
                found.add(messages.getAllValues().get(i));
            }
        }
        return found;
    }

    @Test
    @DisplayName("an unconvertible value read on reload survives an unrelated module save")
    void reloadedTypoSurvivesUnrelatedSave() throws Exception {
        Cfg config = new Cfg(PATH);
        config.init(plugin);
        put(read().replace("interval: 300", "interval: 3O0"));
        config.reload();

        config.message = "changed by module";
        config.save();

        assertThat(read()).contains("interval: 3O0").contains("message: changed by module");
        assertThat(config.interval).as("the declared default runs").isEqualTo(300);
    }

    @Test
    @DisplayName("unconvertible values read at init, whole or one list element, survive an unrelated module save")
    void initTyposSurviveUnrelatedSave() throws Exception {
        put("item:\n  interval: 3O0\n  warn-times: [60, 30, 10, abc]\n  message: hello\n");
        Cfg config = new Cfg(PATH);
        config.init(plugin);

        config.message = "changed by module";
        config.save();

        assertThat(read()).contains("interval: 3O0").contains("abc").contains("message: changed by module");
    }

    @Test
    @DisplayName("a deleted key stays deleted across an unrelated module save")
    void deletedKeyStaysDeletedAcrossUnrelatedSave() throws Exception {
        put("item:\n  interval: 450\n  warn-times: [60, 30, 10]\n  message: hello\n");
        Cfg config = new Cfg(PATH);
        config.init(plugin);
        put("item:\n  warn-times: [60, 30, 10]\n  message: hello\n");
        config.reload();

        config.message = "changed by module";
        config.save();

        assertThat(read()).doesNotContain("interval").contains("message: changed by module");
    }

    @Test
    @DisplayName("when the module changes the unconvertible field itself, the save does not write it and names the key once")
    void moduleChangeOfTheUnconvertibleFieldIsNotWritten() throws Exception {
        Cfg config = new Cfg(PATH);
        config.init(plugin);
        put(read().replace("interval: 300", "interval: 3O0"));
        config.reload();

        config.interval = 120;
        config.save();

        assertThat(read()).contains("interval: 3O0").doesNotContain("120");
        assertThat(config.interval).as("the module's value stays in memory").isEqualTo(120);
        assertThat(unwrittenWarnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("'item.interval'"));
    }

    @Test
    @DisplayName("when the module changes a list holding an unconvertible element, the save does not write it and names the key once")
    void moduleChangeOfAListWithATypoElementIsNotWritten() throws Exception {
        put("item:\n  interval: 300\n  warn-times: [60, 30, 10, abc]\n  message: hello\n");
        Cfg config = new Cfg(PATH);
        config.init(plugin);

        config.warnTimes.add(5);
        config.message = "changed by module";
        config.save();

        assertThat(read()).contains("warn-times: [60, 30, 10, abc]").contains("message: changed by module");
        assertThat(unwrittenWarnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).contains("'item.warn-times'")
                .doesNotContain("abc"));
    }

    @Test
    @DisplayName("the shutdown save of an entity changed elsewhere keeps the unconvertible value")
    void shutdownSaveKeepsTheUnconvertibleValue() throws Exception {
        ConfigManager manager = new ConfigManager();
        Cfg config = new Cfg(PATH);
        manager.register(plugin, config);
        put(read().replace("interval: 300", "interval: 3O0"));
        manager.reloadConfigs(plugin);

        config.message = "changed in memory";
        manager.saveAll();

        assertThat(read()).contains("interval: 3O0").contains("message: changed in memory");
    }

    @Test
    @DisplayName("control: once the operator fixes the value and reloads, a module change of that field saves without an overwrite warning")
    void fixedValueSavesNormally() throws Exception {
        Cfg config = new Cfg(PATH);
        config.init(plugin);
        put(read().replace("interval: 300", "interval: 3O0"));
        config.reload();
        put(read().replace("interval: 3O0", "interval: 450"));
        config.reload();

        config.interval = 120;
        config.save();

        assertThat(read()).contains("interval: 120");
        assertThat(unwrittenWarnings()).isEmpty();
    }
}

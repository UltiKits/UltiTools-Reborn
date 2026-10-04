package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** A refused registration batch must never flush a sibling's initialization writes. */
class ConfigBatchBufferingTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    private ConfigManager manager;

    @BeforeEach
    void setup() {
        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("BatchModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        lenient().when(plugin.i18n(anyString())).thenReturn("Translated note");
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        manager = new ConfigManager();
    }

    @Test
    void refusedBatchCreatesNoEarlierSiblingFile() throws Exception {
        try (MockedStatic<ConverterRegistry> registry = selected(First.class, Second.class, Refused.class)) {
            assertThatThrownBy(() -> manager.registerAll(plugin, "batch", getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("refused");
            assertThat(Files.exists(directory.resolve("first.yml"))).isFalse();
            assertThat(Files.exists(directory.resolve("second.yml"))).isFalse();
            assertThat(Files.exists(directory.resolve("refused.yml"))).isFalse();
            assertEmptyRegistry();
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"batch"}, getClass().getClassLoader()));
        }
    }

    @Test
    void refusedBatchPreservesMissingKeysAndOutdatedTokenComments() throws Exception {
        byte[] first = "# operator header\nvalue: existing\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "# old translation\nvalue: existing\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("first.yml"), first);
        Files.write(directory.resolve("second.yml"), second);
        try (MockedStatic<ConverterRegistry> registry = selected(First.class, Second.class, Refused.class)) {
            assertThatThrownBy(() -> manager.registerAll(plugin, "batch", getClass().getClassLoader()))
                    .isInstanceOf(ConfigurationException.class);
            assertThat(Files.readAllBytes(directory.resolve("first.yml"))).isEqualTo(first);
            assertThat(Files.readAllBytes(directory.resolve("second.yml"))).isEqualTo(second);
            assertEmptyRegistry();
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"batch"}, getClass().getClassLoader()));
        }
    }

    @Test
    void validationSeesNoPendingFilesButAcceptedBatchThenWritesThem() throws Exception {
        Observer.directory = directory;
        Observer.sawFirstFile = false;
        try (MockedStatic<ConverterRegistry> registry = selected(First.class, Second.class, Observer.class)) {
            manager.registerAll(plugin, "batch", getClass().getClassLoader());
            assertThat(Observer.sawFirstFile).isFalse();
            assertThat(Files.exists(directory.resolve("first.yml"))).isTrue();
            assertThat(Files.exists(directory.resolve("second.yml"))).isTrue();
            assertThat(Files.exists(directory.resolve("observer.yml"))).isTrue();
            assertThat(manager.getAllConfigEntities(plugin)).hasSize(3);
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"batch"}, getClass().getClassLoader()));
        }
    }

    @Test
    void outerMultiPackageRefusalDiscardsInnerSuccessfulBatch() throws Exception {
        try (MockedStatic<ConverterRegistry> registry = selected(First.class)) {
            registry.when(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"bad"}, getClass().getClassLoader()))
                    .thenReturn(new LinkedHashSet<>(Arrays.asList(Refused.class)));
            registry.clearInvocations(); // Exclude setup from the bad-package control.
            assertThatThrownBy(() -> manager.registerAll(plugin, new String[]{"batch", "bad"},
                    getClass().getClassLoader())).isInstanceOf(ConfigurationException.class);
            assertThat(Files.exists(directory.resolve("first.yml"))).isFalse();
            assertEmptyRegistry();
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"bad"}, getClass().getClassLoader()));
        }
    }

    @Test
    void standaloneRegistrationRemainsImmediate() throws Exception {
        manager.register(plugin, new First("first.yml"));
        assertThat(Files.readAllBytes(directory.resolve("first.yml")))
                .asString(StandardCharsets.UTF_8).contains("missing: default");
        assertThat(manager.getAllConfigEntities(plugin)).containsKey("first.yml");
    }

    @Test
    void acceptedBatchKeepsEarlierWriteAndProtectsFailedSecondFile() throws Exception {
        // The bare token is the framework's own comment (#604), so the batch flush attempts the comment write.
        byte[] original = "# {note}\nvalue: existing\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("second.yml"), original);
        Observer.directory = directory;
        try (MockedStatic<ConverterRegistry> registry = selected(First.class, Second.class, Observer.class);
                MockedStatic<com.ultikits.ultitools.config.document.AtomicConfigWriter> writer =
                        Mockito.mockStatic(com.ultikits.ultitools.config.document.AtomicConfigWriter.class,
                                Mockito.CALLS_REAL_METHODS)) {
            // Automatic writes publish through the write gate, which stages before its last-moment re-read (17-63 IN-01).
            writer.when(() -> com.ultikits.ultitools.config.document.AtomicConfigWriter.stage(
                    Mockito.eq(directory.resolve("second.yml")), anyString()))
                    .thenThrow(new java.io.IOException("injected second-file refusal"));
            manager.registerAll(plugin, "batch", getClass().getClassLoader());
            assertThat(Files.exists(directory.resolve("first.yml"))).isTrue();
            assertThat(Files.readAllBytes(directory.resolve("second.yml"))).isEqualTo(original);
            assertThat(Files.exists(directory.resolve("observer.yml"))).isTrue();
            Second failed = manager.getConfigEntity(plugin, Second.class);
            assertThat(failed).isNotNull();
            assertThat(failed.isModifiedSinceSnapshot()).isFalse();
            failed.save();
            writer.verify(() -> com.ultikits.ultitools.config.document.AtomicConfigWriter.stage(
                    Mockito.eq(directory.resolve("second.yml")), anyString()), Mockito.times(1));
            writer.verify(() -> com.ultikits.ultitools.config.document.AtomicConfigWriter.write(
                    Mockito.eq(directory.resolve("second.yml")), anyString()), Mockito.never());
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"batch"}, getClass().getClassLoader()));
        }
    }

    @Test
    void protectedInputNeverBecomesPendingWrite() throws Exception {
        byte[] invalid = "value: [broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("first.yml"), invalid);
        try (MockedStatic<ConverterRegistry> registry = selected(First.class)) {
            manager.registerAll(plugin, "batch", getClass().getClassLoader());
            assertThat(Files.readAllBytes(directory.resolve("first.yml"))).isEqualTo(invalid);
            assertThat(manager.getConfigEntity(plugin, First.class).isModifiedSinceSnapshot()).isFalse();
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                    new String[]{"batch"}, getClass().getClassLoader()));
        }
    }

    private MockedStatic<ConverterRegistry> selected(Class<?>... types) {
        MockedStatic<ConverterRegistry> registry = Mockito.mockStatic(ConverterRegistry.class,
                Mockito.CALLS_REAL_METHODS);
        registry.when(() -> ConverterRegistry.prepareSelectedConfigs(plugin,
                new String[]{"batch"}, getClass().getClassLoader()))
                .thenReturn(new LinkedHashSet<>(Arrays.asList(types)));
        registry.clearInvocations(); // Real-method static stubbing is setup, not an operation.
        return registry;
    }

    private void assertEmptyRegistry() {
        Map<String, AbstractConfigEntity> entries = manager.getAllConfigEntities(plugin);
        assertThat(entries == null || entries.isEmpty()).isTrue();
    }

    @ConfigEntity("first.yml")
    public static class First extends AbstractConfigEntity {
        @ConfigEntry(path = "value") String value = "default";
        @ConfigEntry(path = "missing") String missing = "default";
        public First(String path) { super(path); }
    }

    @ConfigEntity("second.yml")
    public static class Second extends AbstractConfigEntity {
        @ConfigEntry(path = "value", comment = "{note}") String value = "default";
        public Second(String path) { super(path); }
    }

    @ConfigEntity("refused.yml")
    public static class Refused extends AbstractConfigEntity {
        @ConfigEntry(path = "value") String value = "default";
        public Refused(String path) { super(path); }
        @Override protected void validateFields() {
            throw new ConfigurationException("BatchModule refused entity validation");
        }
    }

    @ConfigEntity("observer.yml")
    public static class Observer extends AbstractConfigEntity {
        static Path directory;
        static boolean sawFirstFile;
        @ConfigEntry(path = "value") String value = "default";
        public Observer(String path) { super(path); }
        @Override protected void validateFields() {
            sawFirstFile = Files.exists(directory.resolve("first.yml"));
        }
    }
}

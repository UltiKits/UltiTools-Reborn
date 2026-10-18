package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * D-06: {@link UltiToolsPlugin#getAllConfigs()} override handling under auto-registration
 * (SILENT-18 / #336).
 * <p>
 * Mocks {@link ConfigManager} rather than standing up MockBukkit - the assertion is about the
 * diff, not about the package scan itself. Follows the same mock-and-reflect idiom as
 * {@code UltiToolsPluginInitConfigTest}: Mockito's inline mock maker executes the real, private
 * {@code initConfig()} body via reflection, and every overridable method it calls on {@code this}
 * (getAllConfigs, getLogger, getPluginName) is intercepted normally, since Java virtual dispatch
 * does not distinguish "reflectively invoked" from "regularly invoked" callers.
 */
@DisplayName("UltiToolsPlugin.getAllConfigs() diff (D-06 / SILENT-18)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of the private initConfig
class UltiToolsPluginConfigDiffTest {

    // Same mocked-abstract-fixture idiom as UltiToolsPluginInitConfigTest (03-01/03-02):
    // Mockito bypasses the constructor entirely (Objenesis), and with the inline mock maker,
    // mock.getClass() returns the real fixture class, so its annotations are read correctly.

    // Declared before the nested fixture classes below: PMD's
    // FieldDeclarationsShouldBeAtStartOfClass requires fields to precede any inner class.
    private ConfigManager mockConfigManager;

    @UltiToolsModule
    abstract static class AutoRegisterFixture extends UltiToolsPlugin {
    }

    @UltiToolsModule(config = false)
    abstract static class ManualConfigFixture extends UltiToolsPlugin {
    }

    /** Minimal concrete AbstractConfigEntity - only configFilePath matters to the diff. */

    private static class FixtureConfigEntity extends AbstractConfigEntity {
        FixtureConfigEntity(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A directory-backed config entity: ConfigManager expands "kits" into one entity per kits/*.yml. */
    @ConfigEntity("kits")
    static class KitsConfigEntity extends AbstractConfigEntity {
        KitsConfigEntity(String configFilePath) {
            super(configFilePath);
        }
    }

    /** A second directory-backed entity class over the same folder, which the scan does not select. */
    @ConfigEntity("kits")
    static class UnscannedKitsConfigEntity extends AbstractConfigEntity {
        UnscannedKitsConfigEntity(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(mockConfigManager));
    }

    private void invokeInitConfig(UltiToolsPlugin plugin) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("initConfig");
        method.setAccessible(true);
        try {
            method.invoke(plugin);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("Not overridden (interface default, empty list): no throw, no diagnostics")
    void notOverriddenProducesNoThrow() {
        UltiToolsPlugin plugin = mock(AutoRegisterFixture.class);
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);
        // getAllConfigs() left unstubbed -> Mockito's default answer is Collections.emptyList()

        assertThatCode(() -> invokeInitConfig(plugin)).doesNotThrowAnyException();

        verify(mockConfigManager, never()).getAllConfigEntities(any());
        verify(mockLogger, never()).debug(anyString());
    }

    @Test
    @DisplayName("Redundant override (every path already registered): one FINE line, no throw")
    void redundantOverrideLogsOneFineLine() {
        UltiToolsPlugin plugin = mock(AutoRegisterFixture.class);
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);
        when(plugin.getAllConfigs()).thenAnswer(inv ->
                Collections.singletonList(new FixtureConfigEntity("config/redundant.yml")));

        Map<String, AbstractConfigEntity> registered = new HashMap<>();
        registered.put("config/redundant.yml", new FixtureConfigEntity("config/redundant.yml"));
        when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(registered);

        assertThatCode(() -> invokeInitConfig(plugin)).doesNotThrowAnyException();

        verify(mockLogger, times(1)).debug(anyString());
    }

    @Test
    @DisplayName("Override names an entity auto-registration never registered: refuses, names it")
    void lostCapabilityRefusesNamingMissingEntity() {
        UltiToolsPlugin plugin = mock(AutoRegisterFixture.class);
        when(plugin.getPluginName()).thenReturn("TestModule");
        when(plugin.getAllConfigs()).thenAnswer(inv -> Arrays.asList(
                new FixtureConfigEntity("config/known.yml"),
                new FixtureConfigEntity("config/lost.yml")));

        Map<String, AbstractConfigEntity> registered = new HashMap<>();
        registered.put("config/known.yml", new FixtureConfigEntity("config/known.yml"));
        when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(registered);

        assertThatThrownBy(() -> invokeInitConfig(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("TestModule")
                .hasMessageContaining("config/lost.yml");
    }

    @Test
    @DisplayName("config = false: getAllConfigs() is the sole path, no diff runs")
    void configFalseBranchPerformsNoDiff() {
        UltiToolsPlugin plugin = mock(ManualConfigFixture.class);
        when(plugin.getAllConfigs()).thenAnswer(inv -> Arrays.asList(
                new FixtureConfigEntity("config/manual-a.yml"),
                new FixtureConfigEntity("config/manual-b.yml")));

        assertThatCode(() -> invokeInitConfig(plugin)).doesNotThrowAnyException();

        ArgumentCaptor<AbstractConfigEntity> captor = ArgumentCaptor.forClass(AbstractConfigEntity.class);
        try {
            verify(mockConfigManager, times(2)).register(eq(plugin), captor.capture());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        List<String> registeredPaths = new ArrayList<>();
        for (AbstractConfigEntity entity : captor.getAllValues()) {
            registeredPaths.add(entity.getConfigFilePath());
        }
        assertThat(registeredPaths).containsExactlyInAnyOrder("config/manual-a.yml", "config/manual-b.yml");
        verify(mockConfigManager, never()).getAllConfigEntities(any());
    }

    /**
     * Phase 20.4 C43: a {@code getAllConfigs()} override naming a directory-backed config entity.
     * Package-scan auto-registration expands such an entity into one registration per file
     * ({@code kits/starter.yml}, ...) and registers nothing for an empty directory, so the
     * unexpanded path {@code kits} is never a registered key. The diff must instead accept the
     * entity when the path names a directory under the module's resource folder and the scan
     * selected the entity's class. The selected classes are stubbed through
     * {@link ConverterRegistry#prepareSelectedConfigs}, which is what auto-registration itself
     * consults (the real scan returns only top-level classes, and this test's fixtures are nested).
     */
    @Nested
    @DisplayName("Directory-backed override (C43)")
    class DirectoryOverrideTests {

        @TempDir
        File resources;

        private UltiToolsPlugin plugin;
        private PluginLogger mockLogger;

        @BeforeEach
        void setUpModule() {
            plugin = mock(AutoRegisterFixture.class);
            mockLogger = mock(PluginLogger.class);
            when(plugin.getLogger()).thenReturn(mockLogger);
            when(plugin.getPluginName()).thenReturn("KitsModule");
            when(plugin.getResourceFolderPath()).thenReturn(resources.getAbsolutePath());
        }

        private MockedStatic<ConverterRegistry> scanSelects(Class<?>... types) {
            MockedStatic<ConverterRegistry> registry = Mockito.mockStatic(ConverterRegistry.class);
            registry.when(() -> ConverterRegistry.prepareSelectedConfigs(eq(plugin), any(String[].class),
                    any(ClassLoader.class))).thenReturn(new LinkedHashSet<>(Arrays.asList(types)));
            return registry;
        }

        private void verifySelectedWithAutoRegistrationScan(MockedStatic<ConverterRegistry> registry) {
            String[] packages = DependencyUtils.getPluginPackages(plugin);
            ClassLoader loader = UltiTools.getJavaPluginClassLoader();
            registry.verify(() -> ConverterRegistry.prepareSelectedConfigs(eq(plugin), aryEq(packages), eq(loader)));
        }

        private Map<String, String> snapshot() throws IOException {
            final Map<String, String> tree = new TreeMap<>();
            final Path root = resources.toPath();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    String relative = root.relativize(path).toString();
                    tree.put(relative, Files.isDirectory(path) ? "<dir>"
                            : new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                }
            }
            return tree;
        }

        @Test
        @DisplayName("populated kits/ directory, entity class selected by the scan: loads with one FINE line")
        void populatedDirectoryOverrideIsCovered() throws IOException {
            File kits = new File(resources, "kits");
            assertThat(kits.mkdirs()).isTrue();
            Files.write(new File(kits, "starter.yml").toPath(), "items: []\n".getBytes(StandardCharsets.UTF_8));
            when(plugin.getAllConfigs()).thenAnswer(inv ->
                    Collections.singletonList(new KitsConfigEntity("kits")));
            Map<String, AbstractConfigEntity> registered = new HashMap<>();
            registered.put("kits/starter.yml", new KitsConfigEntity("kits/starter.yml"));
            when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(registered);
            Map<String, String> before = snapshot();

            try (MockedStatic<ConverterRegistry> registry = scanSelects(KitsConfigEntity.class)) {
                assertThatCode(() -> invokeInitConfig(plugin)).doesNotThrowAnyException();
                verifySelectedWithAutoRegistrationScan(registry);
            }

            verify(mockLogger, times(1)).debug(anyString());
            verifyNoMoreInteractions(mockLogger);
            assertThat(snapshot()).as("resource folder unchanged").isEqualTo(before);
        }

        @Test
        @DisplayName("empty kits/ directory, entity class selected by the scan: loads")
        void emptyDirectoryOverrideIsCovered() throws IOException {
            assertThat(new File(resources, "kits").mkdirs()).isTrue();
            when(plugin.getAllConfigs()).thenAnswer(inv ->
                    Collections.singletonList(new KitsConfigEntity("kits")));
            when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(new HashMap<>());
            Map<String, String> before = snapshot();

            try (MockedStatic<ConverterRegistry> registry = scanSelects(KitsConfigEntity.class)) {
                assertThatCode(() -> invokeInitConfig(plugin)).doesNotThrowAnyException();
                verifySelectedWithAutoRegistrationScan(registry);
            }

            verify(mockLogger, times(1)).debug(anyString());
            verifyNoMoreInteractions(mockLogger);
            assertThat(snapshot()).as("resource folder unchanged").isEqualTo(before);
        }

        @Test
        @DisplayName("negative control: a directory-backed entity class the scan did not select is refused")
        void unselectedDirectoryEntityIsRefused() throws IOException {
            File kits = new File(resources, "kits");
            assertThat(kits.mkdirs()).isTrue();
            Files.write(new File(kits, "starter.yml").toPath(), "items: []\n".getBytes(StandardCharsets.UTF_8));
            when(plugin.getAllConfigs()).thenAnswer(inv ->
                    Collections.singletonList(new UnscannedKitsConfigEntity("kits")));
            Map<String, AbstractConfigEntity> registered = new HashMap<>();
            registered.put("kits/starter.yml", new KitsConfigEntity("kits/starter.yml"));
            when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(registered);
            Map<String, String> before = snapshot();

            try (MockedStatic<ConverterRegistry> registry = scanSelects(KitsConfigEntity.class)) {
                assertThatThrownBy(() -> invokeInitConfig(plugin))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("KitsModule")
                        .hasMessageContaining("'kits'");
                verifySelectedWithAutoRegistrationScan(registry);
            }
            assertThat(snapshot()).as("resource folder unchanged").isEqualTo(before);
        }

        @Test
        @DisplayName("file-backed path the scan never registered stays refused even when its class was selected")
        void fileBackedOverrideKeepsPathComparison() throws IOException {
            File config = new File(resources, "config");
            assertThat(config.mkdirs()).isTrue();
            Files.write(new File(config, "lost.yml").toPath(), "a: 1\n".getBytes(StandardCharsets.UTF_8));
            when(plugin.getAllConfigs()).thenAnswer(inv ->
                    Collections.singletonList(new KitsConfigEntity("config/lost.yml")));
            when(mockConfigManager.getAllConfigEntities(plugin)).thenReturn(new HashMap<>());

            try (MockedStatic<ConverterRegistry> registry = scanSelects(KitsConfigEntity.class)) {
                assertThatThrownBy(() -> invokeInitConfig(plugin))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("'config/lost.yml'");
                // A path that is not a directory never consults the selected classes.
                registry.verifyNoInteractions();
            }
        }
    }
}

package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
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

    private ConfigManager mockConfigManager;

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
}

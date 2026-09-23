package com.ultikits.ultitools.abstracts;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #531: the {@code final} {@link UltiToolsPlugin#reloadSelf()} template applies a module's
 * reloaded configuration to its config-bound {@code @Scheduled} tasks and {@code @CmdCD}
 * cooldowns -- after the configuration reload succeeded and before the module's own
 * {@code onReload()}. A failed configuration reload skips the step, so the running timings stay.
 * <p>
 * Same mock-and-reflect idiom as {@code UltiToolsPluginLifecycleHookTest}.
 */
@DisplayName("UltiToolsPlugin.reloadSelf() config-binding step (#531)")
class UltiToolsPluginReloadBindingStepTest {

    private ConfigManager configManager;
    private PluginManager pluginManager;

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        configManager = mock(ConfigManager.class);
        pluginManager = mock(PluginManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getConfigManager()).thenReturn(configManager);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // mirrors UltiToolsPluginLifecycleHookTest's reloadSafePlugin
    private FixturePlugin reloadSafePlugin() throws Exception {
        FixturePlugin plugin = mock(FixturePlugin.class);
        when(plugin.getPluginName()).thenReturn("TimingModule");
        when(plugin.getLogger()).thenReturn(mock(PluginLogger.class));
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, System.getProperty("java.io.tmpdir"));
        doCallRealMethod().when(plugin).reloadSelf();
        return plugin;
    }

    @Test
    @DisplayName("the binding step runs after the configuration reload and before onReload()")
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertion IS the InOrder.verify(...) chain
    void theBindingStepRunsAfterConfigReloadAndBeforeOnReload() throws Exception {
        FixturePlugin plugin = reloadSafePlugin();

        plugin.reloadSelf();

        InOrder order = inOrder(configManager, pluginManager, plugin);
        order.verify(configManager).reloadConfigs(plugin);
        order.verify(pluginManager).applyReloadedConfigBindings(plugin);
        order.verify(plugin).onReload();
    }

    @Test
    @DisplayName("a refused configuration reload skips the binding step")
    void aRefusedConfigReloadSkipsTheBindingStep() throws Exception {
        FixturePlugin plugin = reloadSafePlugin();
        doThrow(new ConfigurationException("refused")).when(configManager).reloadConfigs(any());

        assertThrows(ConfigurationException.class, plugin::reloadSelf);

        verify(pluginManager, never()).applyReloadedConfigBindings(any());
    }
}

package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.InOrder;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Pins the combination of two changes to the last statement of {@code PluginManager.unregister},
 * made by two pull requests: the configuration refactor releases a module's configuration entities
 * there, after a shutdown save when the unload is part of {@code close()} (#507 release, #581), and
 * the lifecycle change delists the module there by identity (#507 delist, #564). Both run, even
 * when the module's unload hook throws.
 */
@DisplayName("unregister both releases the module's configuration entities and delists it")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerUnloadReleasesConfigAndDelistsTest {

    private PluginManager pluginManager;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        configManager = mock(ConfigManager.class);
        Logger frameworkLogger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getLogger()).thenReturn(frameworkLogger);
            when(ultiTools.getConfigManager()).thenReturn(configManager);
        });
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static UltiToolsPlugin module(String name) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(name);
        return plugin;
    }

    @Test
    @DisplayName("a runtime unload whose hook throws releases the entities, saves nothing and delists the module")
    void runtimeUnloadWithThrowingHookReleasesAndDelists() {
        UltiToolsPlugin target = module("Target");
        UltiToolsPlugin bystander = module("Bystander");
        IllegalStateException hookFailure = new IllegalStateException("unload hook boom");
        doThrow(hookFailure).when(target).unregisterSelf();
        PluginListSeeding.add(pluginManager, target);
        PluginListSeeding.add(pluginManager, bystander);

        assertThatThrownBy(() -> pluginManager.unregister(target)).isSameAs(hookFailure);

        verify(configManager).unregisterAll(target);
        verify(configManager, never()).saveForShutdown(target);
        assertThat(pluginManager.getPluginList()).containsExactly(bystander);
    }

    @Test
    @DisplayName("close() saves all, then per module saves for shutdown, releases the entities and delists it")
    void closeSavesReleasesAndDelistsEveryModule() {
        UltiToolsPlugin first = module("First");
        UltiToolsPlugin second = module("Second");
        doThrow(new IllegalStateException("second hook boom")).when(second).unregisterSelf();
        PluginListSeeding.add(pluginManager, first);
        PluginListSeeding.add(pluginManager, second);

        pluginManager.close();

        InOrder order = inOrder(configManager);
        order.verify(configManager).saveAll();
        order.verify(configManager).saveForShutdown(first);
        order.verify(configManager).unregisterAll(first);
        order.verify(configManager).saveForShutdown(second);
        order.verify(configManager).unregisterAll(second);
        assertThat(pluginManager.getPluginList()).isEmpty();
    }
}

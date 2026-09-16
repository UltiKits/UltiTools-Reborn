package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * WR-01 (16-REVIEW-lifecycle.md): {@link PluginManager#close()} iterates {@code pluginList}
 * calling {@link PluginManager#unregister(UltiToolsPlugin)} with no per-plugin {@code try/catch}
 * -- one module's {@code onUnregister()} throwing (a realistic risk once Phase 17 migrates
 * modules with real unload work onto this hook) previously aborted the whole loop, skipping
 * every subsequent module's own command/listener/EventBus/PanelResponderRegistry
 * unregistration, {@code pluginList.clear()}, {@code pluginClassList.clear()}, and
 * {@code taskManager.cancelAllCore()}. Because {@code UltiTools.onDisable()} calls
 * {@code pluginManager.close()} unguarded, the exception would also propagate out of
 * {@code onDisable()} and skip every step after it, including {@code configManager.saveAll()}
 * -- a real data-loss risk.
 * <p>
 * Contrast with {@link PluginManager#register(Class)}, which already wraps
 * {@code initializePlugin} in {@code catch (Exception | Error e)} specifically so one module's
 * failure does not prevent others from loading (see {@code logPluginInitializationFailure}). No
 * equivalent isolation existed on the unregister/close path before this fix.
 *
 * @since 6.3.0
 */
@DisplayName("PluginManager.close() isolates one module's unregister() failure from the rest (WR-01)")
class PluginManagerCloseFailureIsolationTest {

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // injecting a mock TaskManager mirrors
    // the reflection idiom already used throughout this package's own field-access tests
    private void injectMockTaskManager(TaskManager mockTaskManager) throws Exception {
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, mockTaskManager);
    }

    @Test
    @DisplayName("one module's unregister() throwing does not skip a later module's unregisterSelf(), pluginList.clear(), or taskManager.cancelAllCore()")
    void oneModuleThrowingDuringCloseDoesNotSkipSubsequentModulesOrCoreTeardown() throws Exception {
        UltiToolsPlugin throwing = mock(UltiToolsPlugin.class);
        when(throwing.getPluginName()).thenReturn("Throwing");
        doThrow(new RuntimeException("module unload boom")).when(throwing).unregisterSelf();

        UltiToolsPlugin healthy = mock(UltiToolsPlugin.class);
        when(healthy.getPluginName()).thenReturn("Healthy");

        pluginManager.getPluginList().add(throwing);
        pluginManager.getPluginList().add(healthy);

        TaskManager mockTaskManager = mock(TaskManager.class);
        injectMockTaskManager(mockTaskManager);

        assertDoesNotThrow(() -> pluginManager.close(),
                "one module's unregister() throwing must not propagate out of close() and skip "
                        + "every remaining teardown step (WR-01)");

        verify(healthy).unregisterSelf();
        verify(mockTaskManager).cancelAllCore();
        assertThat(pluginManager.getPluginList())
                .as("pluginList must still be cleared even though one module's unregister() threw")
                .isEmpty();
    }
}

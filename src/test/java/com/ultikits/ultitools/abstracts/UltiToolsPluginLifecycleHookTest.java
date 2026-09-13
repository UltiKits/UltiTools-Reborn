package com.ultikits.ultitools.abstracts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * D-01/D-02 (#419, #455): {@link UltiToolsPlugin#unregisterSelf()} and {@link
 * UltiToolsPlugin#reloadSelf()} become {@code final} template methods, each delegating to a
 * {@code protected} hook a module CAN override without being able to skip the framework's own
 * body. Before this fix, 15 of 15 module {@code unregisterSelf()} overrides never reached {@link
 * CommandManager#unregisterAll(UltiToolsPlugin)} at all -- measured in {@code 16-INTAKE.md} --
 * because the method was a plain non-final override point, not a template method.
 * <p>
 * Follows the mock-and-reflect idiom proven by {@code UltiToolsPluginLanguageFallbackTest} and
 * {@code ConditionalRegistrationEvaluatorDriftTest}: {@code mock(FixturePlugin.class)} bypasses
 * the constructor (Objenesis, via Mockito's inline mock maker), {@code doCallRealMethod()} runs
 * the REAL {@code unregisterSelf()}/{@code reloadSelf()} body under test, and {@link
 * TestHelper#mockUltiToolsInstance} substitutes mock managers for the static {@code
 * UltiTools.getInstance()} delegation those bodies call through.
 *
 * @since 6.3.0
 */
@DisplayName("UltiToolsPlugin lifecycle hook contract (D-01/D-02, #419/#455)")
class UltiToolsPluginLifecycleHookTest {

    private CommandManager mockCommandManager;
    private ListenerManager mockListenerManager;
    private ConfigManager mockConfigManager;

    /** Bare fixture: overrides neither hook. */
    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    /** Overrides {@code onUnregister()} with real, observable work. */
    abstract static class OverridingOnUnregisterFixturePlugin extends UltiToolsPlugin {
        boolean onUnregisterRan = false;

        @Override
        protected void onUnregister() {
            onUnregisterRan = true;
        }
    }

    @BeforeEach
    void setUp() {
        mockCommandManager = mock(CommandManager.class);
        mockListenerManager = mock(ListenerManager.class);
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
            when(ultiTools.getConfigManager()).thenReturn(mockConfigManager);
        });
    }

    // ==================== Task 1: unregisterSelf() / onUnregister() ====================

    @Test
    @DisplayName("unregisterSelf() is declared final -- deleting the keyword must turn this test red")
    void unregisterSelfIsDeclaredFinal() throws NoSuchMethodException {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("unregisterSelf");
        assertTrue(Modifier.isFinal(method.getModifiers()),
                "UltiToolsPlugin.unregisterSelf() must be final so a module cannot override it "
                        + "and skip the framework's own command/listener unregistration (D-01)");
    }

    @Test
    @DisplayName("a plugin that does not override onUnregister() still has its commands unregistered exactly once")
    void defaultOnUnregisterStillUnregistersCommandsOnce() {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        verify(mockCommandManager, times(1)).unregisterAll(plugin);
    }

    @Test
    @DisplayName("a plugin overriding onUnregister() cannot suppress the framework's own command unregistration")
    void overridingOnUnregisterDoesNotSuppressFrameworkUnregistration() {
        OverridingOnUnregisterFixturePlugin plugin = mock(OverridingOnUnregisterFixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();
        doCallRealMethod().when(plugin).onUnregister();

        plugin.unregisterSelf();

        assertTrue(plugin.onUnregisterRan, "the module's onUnregister() override must have run");
        verify(mockCommandManager, times(1)).unregisterAll(plugin);
    }

    @Test
    @DisplayName("onUnregister() completes before CommandManager.unregisterAll and ListenerManager.unregisterAll, in that order")
    void onUnregisterRunsBeforeFrameworkUnregistersCommandsAndListeners() {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        doCallRealMethod().when(plugin).unregisterSelf();

        plugin.unregisterSelf();

        InOrder order = inOrder(plugin, mockCommandManager, mockListenerManager);
        order.verify(plugin).onUnregister();
        order.verify(mockCommandManager).unregisterAll(plugin);
        order.verify(mockListenerManager).unregisterAll(plugin);
    }
}

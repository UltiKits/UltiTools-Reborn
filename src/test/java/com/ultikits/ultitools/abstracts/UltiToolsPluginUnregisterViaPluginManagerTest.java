package com.ultikits.ultitools.abstracts;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.InOrder;

import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * CR-01/IN-01/WR-03 (16-REVIEW-lifecycle.md): {@link UltiToolsPlugin#unregisterSelf()}'s D-02
 * ordering guarantee -- "{@code onUnregister()} runs before the framework unregisters commands
 * and listeners" -- must hold on the REAL production shutdown path, {@link
 * PluginManager#unregister(UltiToolsPlugin)} / {@link PluginManager#close()}, not only when a
 * test calls {@code unregisterSelf()} directly.
 * <p>
 * {@code UltiToolsPluginLifecycleHookTest} proves the template-method contract in isolation
 * (WR-03's own gap): every one of its 9 tests calls {@code unregisterSelf()}/{@code reloadSelf()}
 * straight on the fixture, never through a real caller. This class drives it through
 * {@code PluginManager.unregister(plugin)} instead, which is exactly what caught CR-01: before
 * the fix, {@code PluginManager.unregister()} called {@code ListenerManager.unregisterAll(plugin)}
 * itself, BEFORE {@code plugin.unregisterSelf()} -- so on this path a module's
 * {@code onUnregister()} observed its own listeners already torn down (contradicting the
 * method's own javadoc), and {@code ListenerManager.unregisterAll(plugin)} ran twice for the same
 * unregister (IN-01, harmless but redundant).
 *
 * @since 6.3.0
 */
@DisplayName("UltiToolsPlugin unregister hook ordering through the real PluginManager chain (CR-01/IN-01/WR-03)")
class UltiToolsPluginUnregisterViaPluginManagerTest {

    private CommandManager mockCommandManager;
    private ListenerManager mockListenerManager;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        mockCommandManager = mock(CommandManager.class);
        mockListenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(mockCommandManager);
            when(ultiTools.getListenerManager()).thenReturn(mockListenerManager);
        });

        pluginManager = new PluginManager();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("PluginManager.unregister(plugin) runs onUnregister() -> commands -> listeners, in that order, and unregisters listeners exactly once")
    void unregisterPreservesHookOrderAndUnregistersListenersOnce() {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("Fixture");
        doCallRealMethod().when(plugin).unregisterSelf();

        pluginManager.unregister(plugin);

        InOrder order = inOrder(plugin, mockCommandManager, mockListenerManager);
        order.verify(plugin).onUnregister();
        order.verify(mockCommandManager).unregisterAll(plugin);
        order.verify(mockListenerManager).unregisterAll(plugin);

        // CR-01 (order) is only half the defect: the pre-fix code additionally called
        // ListenerManager.unregisterAll(plugin) a SECOND time, directly, before
        // unregisterSelf() ran at all (IN-01). Assert the total invocation count, not just
        // relative order -- a fix that only reordered the two calls without removing the
        // duplicate would pass the InOrder check above while leaving IN-01 in place.
        verify(mockListenerManager, times(1)).unregisterAll(plugin);
    }
}

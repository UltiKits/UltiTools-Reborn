package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #503, third unload entry point (issue comment 5713175816): when a newer copy of a module
 * finishes {@code registerSelf()}, {@code PluginManager#unregisterSupersededVersions} unloaded the
 * older copy with {@code existing.unregisterSelf()} alone, bypassing {@link
 * PluginManager#unregister(UltiToolsPlugin)} -- the same root cause as {@code /upm uninstall}. The
 * older copy's {@code @Scheduled} tasks kept firing, its context stayed open, and it stayed in the
 * plugin list next to its replacement.
 * <p>
 * Drives the private {@code attemptPluginRegistration} via reflection, as
 * {@link PluginManagerRegistrationFailureTeardownTest} does, with a real {@link TaskManager} on
 * MockBukkit's scheduler so "the task stopped" is observed, not inferred.
 */
@DisplayName("PluginManager unloads a superseded module version through unregister() (#503)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // private attemptPluginRegistration and taskManager field
class PluginManagerSupersededVersionUnloadTest {

    private static final String MAIN_CLASS = "com.example.SupersededFixture";

    private ServerMock server;
    private PluginManager pluginManager;
    private TaskManager taskManager;
    private CommandManager commandManager;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        PluginMock host = MockBukkit.createMockPlugin("SupersededHost");

        commandManager = mock(CommandManager.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
        });

        pluginManager = new PluginManager();
        taskManager = new TaskManager(host);
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(pluginManager, taskManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    /** A module bean carrying one repeating {@code @Scheduled} task that counts its own runs. */
    public static class TickingBean {
        private final AtomicInteger runs = new AtomicInteger();

        @Scheduled(period = 1)
        public void tick() {
            runs.incrementAndGet();
        }
    }

    private static UltiToolsPlugin moduleCopy(String version, SimpleContainer context) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn("SupersededFixture");
        when(plugin.getMainClass()).thenReturn(MAIN_CLASS);
        when(plugin.getVersion()).thenReturn(version);
        when(plugin.getContext()).thenReturn(context);
        doCallRealMethod().when(plugin).isNewerVersionThan(any());
        doCallRealMethod().when(plugin).unregisterSelf();
        return plugin;
    }

    @Test
    @DisplayName("the older copy's @Scheduled task stops, its context closes and it leaves the plugin list; the newer copy stays")
    void newerCopyRegistered_unloadsOlderCopyThroughUnregister() throws Exception {
        SimpleContainer oldContext = mock(SimpleContainer.class);
        UltiToolsPlugin oldCopy = moduleCopy("1.0.0", oldContext);
        pluginManager.getPluginList().add(oldCopy);
        TickingBean oldBean = new TickingBean();
        taskManager.registerScheduledMethods(oldCopy, oldBean);

        server.getScheduler().performTicks(5);
        int runsBeforeReplacement = oldBean.runs.get();
        assertThat(runsBeforeReplacement)
                .as("control: the older copy's task must be running before the replacement, "
                        + "otherwise 'it stopped' below would pass vacuously")
                .isPositive();

        UltiToolsPlugin newCopy = moduleCopy("2.0.0", mock(SimpleContainer.class));
        when(newCopy.registerSelf()).thenReturn(true);
        Method attempt = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        attempt.setAccessible(true);
        assertThat((boolean) attempt.invoke(pluginManager, newCopy)).isTrue();

        server.getScheduler().performTicks(10);
        assertThat(oldBean.runs.get())
                .as("a superseded copy's @Scheduled task must stop -- only PluginManager#unregister "
                        + "cancels it, and the superseded unload must go through it (#503)")
                .isEqualTo(runsBeforeReplacement);
        verify(oldContext).close();
        assertThat(pluginManager.getPluginList())
                .as("the superseded copy must leave the plugin list; its replacement must be in it")
                .doesNotContain(oldCopy)
                .contains(newCopy);
    }

    @Test
    @DisplayName("an older copy whose unload throws is still closed and removed, and does not fail the newer copy's already-activated load")
    void olderCopyUnloadThrows_newerCopyStillLoadsAndOlderCopyIsRemoved() throws Exception {
        SimpleContainer oldContext = mock(SimpleContainer.class);
        UltiToolsPlugin oldCopy = moduleCopy("1.0.0", oldContext);
        // unregisterSelf() rethrows its first failed step; a command-cleanup failure stands in for
        // any throwing unload step (onUnregister() itself is protected and not stubbable from here).
        org.mockito.Mockito.doThrow(new IllegalStateException("old copy's unload step boom"))
                .when(commandManager).unregisterAll(oldCopy);
        pluginManager.getPluginList().add(oldCopy);

        UltiToolsPlugin newCopy = moduleCopy("2.0.0", mock(SimpleContainer.class));
        when(newCopy.registerSelf()).thenReturn(true);
        Method attempt = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        attempt.setAccessible(true);

        // The newer copy's registerSelf() has already returned true -- it is active. Failing its
        // load because the copy it replaces threw on the way out would leave it activated but
        // unlisted and its context open, the "neither version" outcome the unload ordering exists
        // to prevent. The failure is logged, the same way close() treats one module's throwing
        // unregister().
        assertThat((boolean) attempt.invoke(pluginManager, newCopy)).isTrue();
        verify(oldContext).close();
        assertThat(pluginManager.getPluginList())
                .doesNotContain(oldCopy)
                .contains(newCopy);
    }
}

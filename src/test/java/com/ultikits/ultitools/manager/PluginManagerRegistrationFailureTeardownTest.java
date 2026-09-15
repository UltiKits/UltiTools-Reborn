package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Gate-1 review WR-02 (#410): {@code TaskManager.scanAndSchedule}'s own javadoc claims "whatever
 * the loop reached before throwing is already exactly where {@code cancelAll}... will find it at
 * teardown" -- true only if something actually calls teardown after such a throw. Traced to
 * {@code PluginManager.attemptPluginRegistration}: its outer {@code catch (Exception | Error e)}
 * logged and returned {@code false} WITHOUT unregistering a plugin whose
 * {@code onPluginRegistered} had already run {@code pluginList.add(plugin)} before a LATER
 * bean's own scheduling call threw. This suite drives {@code attemptPluginRegistration} directly
 * (via reflection -- it is private, and this is a framework-internal registration-lifecycle
 * detail, not a public contract) with a real {@link TaskManager} and a real, mocked
 * {@link BukkitScheduler} that fails deterministically on the SECOND scheduling call, exactly
 * like {@link TaskManagerTest.MidScanFailureTests}.
 */
@DisplayName("PluginManager registration teardown on a mid-scan task-registration failure (WR-02, #410)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // invokes the private attemptPluginRegistration via reflection
class PluginManagerRegistrationFailureTeardownTest {

    private PluginManager pluginManager;
    private TaskManager taskManager;
    private JavaPlugin mockHostPlugin;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        mockHostPlugin = MockBukkit.createMockPlugin();

        ListenerManager listenerManager = mock(ListenerManager.class);
        EventBus eventBus = mock(EventBus.class);
        PanelResponderRegistry panelResponderRegistry = mock(PanelResponderRegistry.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
            when(ultiTools.getEventBus()).thenReturn(eventBus);
            when(ultiTools.getPanelResponderRegistry()).thenReturn(panelResponderRegistry);
        });

        pluginManager = new PluginManager();
        // The no-arg PluginManager() constructor does not call init(ClassLoader), so
        // taskManager is null until this test wires a real one directly -- exactly the
        // instance whose mid-scan-failure contract (#410) this suite is proving end to end
        // through the surrounding caller, not in isolation.
        taskManager = new TaskManager(mockHostPlugin);
        setField(pluginManager, "taskManager", taskManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = PluginManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean invokeAttemptPluginRegistration(PluginManager pm, UltiToolsPlugin plugin) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("attemptPluginRegistration", UltiToolsPlugin.class);
        method.setAccessible(true);
        return (boolean) method.invoke(pm, plugin);
    }

    private UltiToolsPlugin failingModuleWithTwoScheduledMethodsBean(SimpleContainer context) throws Exception {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.registerSelf()).thenReturn(true);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getPluginName()).thenReturn("FailingModule");
        when(plugin.getMainClass()).thenReturn("com.example.FailingModule");
        return plugin;
    }

    @Test
    @DisplayName("a registration whose task-scan throws after pluginList.add(plugin) leaves no "
            + "live task and no half-loaded plugin -- unregister() runs on that failure path")
    void registrationFailureAfterPartialTaskSchedulingTearsDown() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getLogger).thenReturn(
                    Logger.getLogger("PluginManagerRegistrationFailureTeardownTest.tearsDown"));

            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            BukkitTask firstTask = mock(BukkitTask.class);
            when(mockScheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(firstTask)
                    .thenThrow(new RuntimeException(
                            "simulated: host plugin became disabled mid-registration"));

            SimpleContainer context = new SimpleContainer();
            context.registerSingleton("bean", new TaskManagerTest.TwoScheduledMethodsBean());

            UltiToolsPlugin plugin = failingModuleWithTwoScheduledMethodsBean(context);

            boolean result = invokeAttemptPluginRegistration(pluginManager, plugin);

            assertFalse(result,
                    "attemptPluginRegistration must itself catch the mid-scan exception and "
                            + "return false -- the exception must not propagate out to register()");
            assertFalse(pluginManager.getPluginList().contains(plugin),
                    "WR-02: a plugin whose registration failed AFTER pluginList.add(plugin) had "
                            + "already run must not be left in pluginList");
            assertEquals(0, taskManager.getTaskCount(plugin),
                    "WR-02: the task scheduled BEFORE the throwing one must be cancelled at "
                            + "teardown, not left live and uncancellable, when registration "
                            + "itself ultimately fails");
            verify(firstTask).cancel();
            verify(plugin).unregisterSelf();
        }
    }

    @Test
    @DisplayName("unregister() itself throwing during this teardown does not let a second "
            + "exception escape attemptPluginRegistration, and still removes the plugin from pluginList")
    void unregisterFailureDuringTeardownIsHandledAndPluginStillRemoved() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getLogger).thenReturn(
                    Logger.getLogger("PluginManagerRegistrationFailureTeardownTest.unregisterFails"));

            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
            when(mockScheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(mock(BukkitTask.class))
                    .thenThrow(new RuntimeException("simulated: host plugin became disabled mid-registration"));

            SimpleContainer context = new SimpleContainer();
            context.registerSingleton("bean", new TaskManagerTest.TwoScheduledMethodsBean());

            UltiToolsPlugin plugin = failingModuleWithTwoScheduledMethodsBean(context);
            // unregisterSelf() itself throwing is the concrete "unregister() can itself throw"
            // case the review named -- this must not surface as a SECOND uncaught exception out
            // of attemptPluginRegistration.
            org.mockito.Mockito.doThrow(new IllegalStateException("module's own teardown is broken"))
                    .when(plugin).unregisterSelf();

            boolean result = assertDoesNotThrowAndReturn(
                    () -> invokeAttemptPluginRegistration(pluginManager, plugin));

            assertFalse(result);
            assertFalse(pluginManager.getPluginList().contains(plugin),
                    "even when unregister() itself throws partway, the plugin must still end up "
                            + "removed from pluginList -- a half-torn-down plugin staying in the "
                            + "list would be worse than the original gap");
        }
    }

    private static boolean assertDoesNotThrowAndReturn(java.util.concurrent.Callable<Boolean> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new AssertionError("attemptPluginRegistration must not let a second exception "
                    + "escape when unregister() itself fails during teardown", e);
        }
    }
}

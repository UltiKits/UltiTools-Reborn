package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.aop.ProxyFactory;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Tests for TaskManager and @Scheduled annotation.
 */
@DisplayName("TaskManager Tests")
@Tag("isolated")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TaskManagerTest {

    private ServerMock server;
    private JavaPlugin mockPlugin;
    private TaskManager taskManager;
    private UltiToolsPlugin mockUltiPlugin;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        taskManager = new TaskManager(mockPlugin);
        mockUltiPlugin = mock(UltiToolsPlugin.class);
        when(mockUltiPlugin.getPluginName()).thenReturn("TestPlugin");
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // === Test beans ===

    public static class ServiceWithScheduled {
        public int syncCallCount = 0;
        public int asyncCallCount = 0;

        @Scheduled(period = 20)
        public void syncTask() {
            syncCallCount++;
        }

        @Scheduled(period = 40, async = true)
        public void asyncTask() {
            asyncCallCount++;
        }
    }

    public static class ServiceWithDelayedTask {
        public int callCount = 0;

        @Scheduled(delay = 100)
        public void delayedOneShot() {
            callCount++;
        }
    }

    public static class ServiceWithNoScheduled {
        public void regularMethod() {
            // No @Scheduled
        }
    }

    public static class ServiceWithInvalidMethod {
        @Scheduled(period = 20)
        public void invalidWithParam(String param) {
            // Has parameter — should be skipped
        }
    }

    public static class ServiceWithNonVoidReturn {
        @Scheduled(period = 20)
        public String invalidReturn() {
            return "should be skipped";
        }
    }

    // === #410 fixtures: beans whose scheduling is driven entirely through the mocked
    // BukkitScheduler below, not through MockBukkit's own real scheduler ===

    public static class OneScheduledMethodBean {
        @Scheduled(period = 20)
        public void only() {
            // Never actually invoked in the #410 tests -- scheduling itself is what fails.
        }
    }

    public static class TwoScheduledMethodsBean {
        @Scheduled(period = 20)
        public void first() {
            // Never actually invoked in the #410 tests -- scheduling itself is what fails.
        }

        @Scheduled(period = 30)
        public void second() {
            // Never actually invoked in the #410 tests -- scheduling itself is what fails.
        }
    }

    // === Annotation Tests ===

    @Nested
    @DisplayName("@Scheduled Annotation")
    class AnnotationTests {

        @Test
        @DisplayName("Should have correct default values")
        void defaultValues() throws NoSuchMethodException {
            Method method = ServiceWithDelayedTask.class.getDeclaredMethod("delayedOneShot");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertNotNull(scheduled);
            assertEquals(100, scheduled.delay());
            assertEquals(-1, scheduled.period()); // Default: one-shot
            assertFalse(scheduled.async());       // Default: sync
        }

        @Test
        @DisplayName("Should read custom period and async values")
        void customValues() throws NoSuchMethodException {
            Method method = ServiceWithScheduled.class.getDeclaredMethod("asyncTask");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertNotNull(scheduled);
            assertEquals(40, scheduled.period());
            assertTrue(scheduled.async());
        }

        @Test
        @DisplayName("Should read delay for one-shot task")
        void delayValue() throws NoSuchMethodException {
            Method method = ServiceWithDelayedTask.class.getDeclaredMethod("delayedOneShot");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertNotNull(scheduled);
            assertEquals(100, scheduled.delay());
            assertEquals(-1, scheduled.period()); // One-shot
        }
    }

    // === Registration Tests ===

    @Nested
    @DisplayName("Task Registration")
    class RegistrationTests {

        @Test
        @DisplayName("Should register scheduled methods as tasks")
        void shouldRegisterScheduledMethods() {
            ServiceWithScheduled bean = new ServiceWithScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(2, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should not register bean with no @Scheduled methods")
        void shouldSkipBeanWithNoScheduled() {
            ServiceWithNoScheduled bean = new ServiceWithNoScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should skip methods with parameters")
        void shouldSkipMethodWithParams() {
            ServiceWithInvalidMethod bean = new ServiceWithInvalidMethod();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should skip methods with non-void return type")
        void shouldSkipNonVoidReturn() {
            ServiceWithNonVoidReturn bean = new ServiceWithNonVoidReturn();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should register one-shot delayed task")
        void shouldRegisterOneShotTask() {
            ServiceWithDelayedTask bean = new ServiceWithDelayedTask();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(1, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should accumulate tasks from multiple beans")
        void shouldAccumulateFromMultipleBeans() {
            ServiceWithScheduled bean1 = new ServiceWithScheduled();
            ServiceWithDelayedTask bean2 = new ServiceWithDelayedTask();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean1);
            taskManager.registerScheduledMethods(mockUltiPlugin, bean2);

            assertEquals(3, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("Should discover @Scheduled methods on a ByteBuddy-proxied bean (regression for #188)")
        void shouldRegisterScheduledMethodsOnProxiedBean() throws Exception {
            // Overriding methods do not inherit annotations, so if TaskManager fails to unwrap
            // the proxy back to the original class, getDeclaredMethods() finds no @Scheduled
            // methods at all and this silently registers zero tasks instead of throwing.
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());
            Set<Method> intercepted = new LinkedHashSet<>(Arrays.asList(
                    ServiceWithScheduled.class.getMethod("syncTask"),
                    ServiceWithScheduled.class.getMethod("asyncTask")));
            ServiceWithScheduled proxy = proxyFactory
                    .createProxyClass(ServiceWithScheduled.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            taskManager.registerScheduledMethods(mockUltiPlugin, proxy);

            assertEquals(2, taskManager.getTaskCount(mockUltiPlugin),
                    "TaskManager must unwrap ByteBuddy proxies via ProxyFactory.isProxyClass() "
                            + "to find @Scheduled methods on the proxied bean");
        }
    }

    // === Execution Tests ===

    @Nested
    @DisplayName("Task Execution")
    class ExecutionTests {

        @Test
        @DisplayName("Sync repeating task should execute when ticks advance")
        void syncRepeatingTaskExecutes() {
            ServiceWithScheduled bean = new ServiceWithScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            // Advance server ticks to trigger the task
            // period=20, delay=0, so task runs at tick 0, 20, 40...
            server.getScheduler().performTicks(21);

            assertTrue(bean.syncCallCount > 0, "Sync task should have executed at least once");
        }

        @Test
        @DisplayName("One-shot delayed task should execute after delay")
        void oneShotDelayedTaskExecutes() {
            ServiceWithDelayedTask bean = new ServiceWithDelayedTask();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            // delay=100, so advance past that
            server.getScheduler().performTicks(101);

            assertEquals(1, bean.callCount, "Delayed task should have executed exactly once");
        }
    }

    // === Cancellation Tests ===

    @Nested
    @DisplayName("Task Cancellation")
    class CancellationTests {

        @Test
        @DisplayName("cancelAll should remove all tasks for a plugin")
        void cancelAllRemovesTasks() {
            ServiceWithScheduled bean = new ServiceWithScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);
            assertEquals(2, taskManager.getTaskCount(mockUltiPlugin));

            taskManager.cancelAll(mockUltiPlugin);
            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("cancelAll should stop task execution")
        void cancelAllStopsExecution() {
            ServiceWithScheduled bean = new ServiceWithScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            // Let it run once
            server.getScheduler().performTicks(21);
            int countAfterFirstRun = bean.syncCallCount;
            assertTrue(countAfterFirstRun > 0);

            // Cancel all tasks
            taskManager.cancelAll(mockUltiPlugin);

            // Advance more ticks — count should not increase
            server.getScheduler().performTicks(100);
            assertEquals(countAfterFirstRun, bean.syncCallCount,
                    "Task should not execute after cancellation");
        }

        @Test
        @DisplayName("cancelAll on plugin with no tasks should not throw")
        void cancelAllNoTasksNoThrow() {
            UltiToolsPlugin otherPlugin = mock(UltiToolsPlugin.class);
            assertDoesNotThrow(() -> taskManager.cancelAll(otherPlugin));
        }

        @Test
        @DisplayName("cancelAll should only affect the specified plugin")
        void cancelAllOnlyAffectsTargetPlugin() {
            UltiToolsPlugin otherPlugin = mock(UltiToolsPlugin.class);
            ServiceWithScheduled bean1 = new ServiceWithScheduled();
            ServiceWithDelayedTask bean2 = new ServiceWithDelayedTask();

            taskManager.registerScheduledMethods(mockUltiPlugin, bean1);
            taskManager.registerScheduledMethods(otherPlugin, bean2);

            taskManager.cancelAll(mockUltiPlugin);

            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
            assertEquals(1, taskManager.getTaskCount(otherPlugin));
        }
    }

    // === Framework-owned (core) bucket -- #384 ===

    @Nested
    @DisplayName("Framework-owned task registration (#384)")
    class CoreTaskTests {

        @Test
        @DisplayName("Should register a framework-owned bean's scheduled methods")
        void shouldRegisterCoreBean() {
            taskManager.registerScheduledMethodsCore(new ServiceWithScheduled());

            assertEquals(2, taskManager.getCoreTaskCount());
        }

        @Test
        @DisplayName("Core registration does not leak into the per-plugin bucket")
        void coreRegistrationIsSeparateFromPluginBucket() {
            taskManager.registerScheduledMethodsCore(new ServiceWithScheduled());

            assertEquals(2, taskManager.getCoreTaskCount());
            assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
        }

        @Test
        @DisplayName("cancelAllCore cancels framework tasks and leaves plugin tasks alone")
        void cancelAllCoreOnlyAffectsCoreBucket() {
            taskManager.registerScheduledMethodsCore(new ServiceWithScheduled());
            taskManager.registerScheduledMethods(mockUltiPlugin, new ServiceWithDelayedTask());

            taskManager.cancelAllCore();

            assertEquals(0, taskManager.getCoreTaskCount());
            assertEquals(1, taskManager.getTaskCount(mockUltiPlugin));
        }

        /**
         * The concrete #384 regression, stated against the real class rather than a test double.
         * <p>
         * {@code PlayerCacheManager.sweepExpiredEntries()} carries {@code @Scheduled} and was
         * measured on a live server never to run: the framework had no path that scanned an object
         * it constructs itself. A test double would prove the new bucket works in the abstract;
         * this proves the actual class whose annotation was inert is now registered.
         */
        @Test
        @DisplayName("PlayerCacheManager's expiry sweep is registered -- the #384 regression")
        void playerCacheManagerSweepIsRegistered() {
            taskManager.registerScheduledMethodsCore(new PlayerCacheManager());

            assertEquals(1, taskManager.getCoreTaskCount(),
                    "PlayerCacheManager declares exactly one @Scheduled method "
                            + "(sweepExpiredEntries); before #384 it was registered zero times");
        }
    }

    // === #410: scanAndSchedule must not leak already-scheduled tasks when a LATER
    // @Scheduled method's own scheduling call throws mid-scan ===
    //
    // Bukkit.getScheduler() is replaced with a Mockito mock for these tests (rather than
    // relying on MockBukkit's real BukkitSchedulerMock, which never rejects a scheduling call
    // regardless of plugin state) so the SECOND scheduling call can be made to throw
    // deterministically, exactly as the real IllegalPluginAccessException would if the host
    // plugin became disabled mid-registration.
    @Nested
    @DisplayName("scanAndSchedule does not leak already-scheduled tasks on a mid-scan failure (#410)")
    class MidScanFailureTests {

        @Test
        @DisplayName("a bean with two @Scheduled methods where the second throws still records "
                + "the first task in its owning bucket, and that task is cancellable")
        void earlierTaskSurvivesALaterThrowAndStaysCancellable() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkit.when(Bukkit::getLogger).thenReturn(
                        Logger.getLogger("TaskManagerTest.MidScanFailureTests.earlierTaskSurvives"));

                BukkitTask firstTask = mock(BukkitTask.class);
                when(mockScheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                        .thenReturn(firstTask)
                        .thenThrow(new RuntimeException(
                                "simulated: host plugin became disabled mid-registration"));

                TwoScheduledMethodsBean bean = new TwoScheduledMethodsBean();

                assertThrows(RuntimeException.class,
                        () -> taskManager.registerScheduledMethods(mockUltiPlugin, bean),
                        "the second method's scheduling failure must still propagate -- this "
                                + "test is about what survives it, not about swallowing it");

                assertEquals(1, taskManager.getTaskCount(mockUltiPlugin),
                        "the task scheduled BEFORE the throwing one must already be recorded in "
                                + "its owning bucket -- not lost because scanAndSchedule never "
                                + "reached its own return statement (#410)");

                // Not merely counted -- genuinely cancellable at teardown.
                assertDoesNotThrow(() -> taskManager.cancelAll(mockUltiPlugin));
                verify(firstTask).cancel();
                assertEquals(0, taskManager.getTaskCount(mockUltiPlugin));
            }
        }

        @Test
        @DisplayName("a bean with a single @Scheduled method that throws leaves nothing behind")
        void singleScheduledMethodThatThrowsLeavesNothingBehind() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkit.when(Bukkit::getLogger).thenReturn(
                        Logger.getLogger("TaskManagerTest.MidScanFailureTests.singleThrows"));

                when(mockScheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                        .thenThrow(new RuntimeException("simulated scheduling failure"));

                OneScheduledMethodBean bean = new OneScheduledMethodBean();

                assertThrows(RuntimeException.class,
                        () -> taskManager.registerScheduledMethods(mockUltiPlugin, bean));

                assertEquals(0, taskManager.getTaskCount(mockUltiPlugin),
                        "a single scheduled method that throws immediately must leave nothing "
                                + "recorded -- there was never a successfully-scheduled task to lose");
            }
        }

        @Test
        @DisplayName("a successful scan still records every created task in its owning bucket, as before")
        void successfulScanStillRecordsEveryCreatedTask() {
            ServiceWithScheduled bean = new ServiceWithScheduled();
            taskManager.registerScheduledMethods(mockUltiPlugin, bean);

            assertEquals(2, taskManager.getTaskCount(mockUltiPlugin),
                    "the happy path (no exception) must be unaffected by recording tasks "
                            + "immediately instead of batching them at the end of the scan");
        }
    }
}

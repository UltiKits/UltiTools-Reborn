package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
}

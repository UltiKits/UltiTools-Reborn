package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #532: {@code TaskManager} finds {@code @Scheduled} methods declared on superclasses of a bean, as
 * {@code @Scheduled}'s javadoc has always said, and schedules an overridden method once, with the
 * most derived declaration's annotation. Spring's {@code ScheduledAnnotationBeanPostProcessor}
 * selects scheduled methods over the class hierarchy the same way.
 * <p>
 * Before the fix the scan read {@code getDeclaredMethods()} of the bean's own class only, so a
 * {@code @Scheduled} method inherited from an abstract base service was silently never scheduled.
 */
@DisplayName("@Scheduled methods declared on a superclass are scheduled (#532)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TaskManagerInheritedScheduledTest {

    private ServerMock server;
    private TaskManager taskManager;
    private UltiToolsPlugin module;
    private final List<String> registrations = new ArrayList<>();
    private Handler capture;

    /** A base service whose subclass is the bean. */
    public abstract static class AbstractTickingService {
        public int baseTicks;

        @Scheduled(period = 20)
        public void baseTick() {
            baseTicks++;
        }
    }

    public static class ConcreteTickingService extends AbstractTickingService {
        // Inherits baseTick() and its @Scheduled.
    }

    public abstract static class AbstractOverriddenService {
        @Scheduled(period = 20)
        public void tick() {
            // Overridden below.
        }
    }

    public static class ReannotatingService extends AbstractOverriddenService {
        public int ticks;

        @Override
        @Scheduled(period = 40)
        public void tick() {
            ticks++;
        }
    }

    public static class UnannotatedOverrideService extends AbstractOverriddenService {
        @Override
        public void tick() {
            // An override without @Scheduled: annotations on methods are not inherited.
        }
    }

    public abstract static class AbstractBoundService {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period")
        public void boundTick() {
            // Only its binding is inspected.
        }
    }

    public static class ConcreteBoundService extends AbstractBoundService {
        // Inherits the bound boundTick().
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        JavaPlugin host = MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        taskManager = new TaskManager(host);
        module = mock(UltiToolsPlugin.class);
        when(module.getPluginName()).thenReturn("InheritModule");
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null && record.getMessage().contains("Registered @Scheduled task")) {
                    registrations.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
                // Nothing buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Bukkit.getLogger().addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(capture);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("a @Scheduled method declared on an abstract base is scheduled and runs")
    void inheritedScheduledMethodIsScheduledAndRuns() {
        ConcreteTickingService bean = new ConcreteTickingService();

        taskManager.registerScheduledMethods(module, bean);
        server.getScheduler().performTicks(41);

        assertEquals(1, taskManager.getTaskCount(module));
        assertTrue(bean.baseTicks >= 2, "the inherited task must actually run, ran " + bean.baseTicks);
    }

    @Test
    @DisplayName("an overridden method is scheduled once, with the subclass's annotation")
    void overriddenMethodIsScheduledOnceWithTheSubclassAnnotation() {
        ReannotatingService bean = new ReannotatingService();

        taskManager.registerScheduledMethods(module, bean);

        assertEquals(1, taskManager.getTaskCount(module), "one task per method, not one per declaration");
        assertEquals(1, registrations.size());
        assertTrue(registrations.get(0).contains("period=40"),
                "the most derived declaration's annotation wins: " + registrations);
    }

    @Test
    @DisplayName("an override without @Scheduled is not scheduled")
    void unannotatedOverrideIsNotScheduled() {
        taskManager.registerScheduledMethods(module, new UnannotatedOverrideService());

        assertEquals(0, taskManager.getTaskCount(module));
    }

    @Test
    @DisplayName("a proxied bean still finds the method its unproxied superclass declares")
    void proxiedBeanFindsTheInheritedMethod() throws Exception {
        ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());
        Set<Method> intercepted = new LinkedHashSet<>(
                Arrays.asList(ConcreteTickingService.class.getMethod("baseTick")));
        ConcreteTickingService proxy = proxyFactory
                .createProxyClass(ConcreteTickingService.class, intercepted)
                .getDeclaredConstructor().newInstance();

        taskManager.registerScheduledMethods(module, proxy);

        assertEquals(1, taskManager.getTaskCount(module));
    }

    @Test
    @DisplayName("the load-time binding checks see an inherited bound method too")
    void loadTimeBindingChecksSeeTheInheritedMethod() {
        assertEquals("ConcreteBoundService.boundTick", TaskManager.firstBoundMethod(new ConcreteBoundService()),
                "firstBoundMethod must see the bound method the abstract base declares");
    }
}

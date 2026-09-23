package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #531: a {@code @Scheduled} period or delay bound to a module config key.
 * <p>
 * Timing assertions run on MockBukkit's real scheduler, whose semantics were measured before
 * these tests were written: a task scheduled at tick {@code t} with delay {@code d >= 1} first
 * runs at tick {@code t + d}, a delay of {@code 0} runs at {@code t + 1}, and a repeating task
 * then runs every {@code period} ticks. Every test registers at tick 0, so a bound period of
 * {@code P} seconds with the default delay runs at ticks {@code 1, 1 + 20P, 1 + 40P, ...}.
 * <p>
 * The reload rule under test is the maintainer's decision for #531: the next run after a reload
 * is the last run plus the new interval, or the next tick when that moment has already passed;
 * before the first run it is the arm tick plus the new delay. A reload therefore never runs a task
 * early and never postpones it by restarting its clock.
 */
@DisplayName("TaskManager config-bound @Scheduled (#531)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TaskManagerConfigBindingTest {

    private ServerMock server;
    private JavaPlugin host;
    private TaskManager taskManager;
    private UltiToolsPlugin module;
    private ConfigManager configManager;
    private BindingTimingConfig config;
    private final List<LogRecord> logs = new ArrayList<>();
    private Handler logCapture;

    // === Fixtures ===

    /** Bound period, default delay. */
    public static class BoundPeriodBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period")
        public void tick() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    /** The interest pattern: delay and period bound to the same key. */
    public static class BoundDelayAndPeriodBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", delayKey = "timer.period")
        public void tick() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    /** Bound to a boxed field, so a {@code null} value can be tried on reload. */
    public static class BoundBoxedBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.boxed")
        public void tick() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    /** Async, both keys bound. */
    public static class BoundAsyncBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", delayKey = "timer.period",
                async = true)
        public void tick() {
            // Scheduling is what is asserted; the body never runs under the mocked scheduler.
        }
    }

    /** Async, period only (default delay). */
    public static class BoundAsyncPeriodOnlyBean {
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", async = true)
        public void tick() {
            // Scheduling is what is asserted; the body never runs under the mocked scheduler.
        }
    }

    /** A one-shot whose delay is bound (period left at its run-once literal). */
    public static class BoundDelayOnlyBean {
        public final List<Integer> fireTicks = new ArrayList<>();

        @Scheduled(config = BindingTimingConfig.class, delayKey = "timer.period")
        public void once() {
            fireTicks.add(Bukkit.getCurrentTick());
        }
    }

    /** Literal only -- today's shape, which must stay byte-identical. */
    public static class LiteralBean {
        @Scheduled(delay = 7, period = 20)
        public void tick() {
            // Scheduling is what is asserted.
        }
    }

    /** Literal one-shot. */
    public static class LiteralOneShotBean {
        @Scheduled(delay = 100)
        public void once() {
            // Scheduling is what is asserted.
        }
    }

    /** Literal async repeating. */
    public static class LiteralAsyncBean {
        @Scheduled(period = 40, async = true)
        public void tick() {
            // Scheduling is what is asserted.
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        host = MockBukkit.createMockPlugin();
        config = new BindingTimingConfig();
        configManager = mock(ConfigManager.class);
        module = mock(UltiToolsPlugin.class);
        lenient().when(module.getPluginName()).thenReturn("TimingModule");
        lenient().when(configManager.getConfigEntities(module, BindingTimingConfig.class))
                .thenReturn(Collections.singletonList(config));
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(configManager));
        taskManager = new TaskManager(host);

        logCapture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // Records are appended straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Bukkit.getLogger().addHandler(logCapture);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(logCapture);
        MockBukkitHelper.safeUnmock();
    }

    // === Helpers ===

    /** Advance the real MockBukkit scheduler until {@code Bukkit.getCurrentTick() == tick}. */
    private void advanceTo(int tick) {
        int now = Bukkit.getCurrentTick();
        assertTrue(tick >= now, "cannot advance backwards from " + now + " to " + tick);
        server.getScheduler().performTicks(tick - now);
        assertEquals(tick, Bukkit.getCurrentTick());
    }

    private Set<Integer> pendingTaskIds() {
        Set<Integer> ids = new TreeSet<>();
        for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (!task.isCancelled()) {
                ids.add(task.getTaskId());
            }
        }
        return ids;
    }

    private List<String> messagesAt(Level level) {
        List<String> messages = new ArrayList<>();
        for (LogRecord record : logs) {
            if (level.equals(record.getLevel())) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    private static List<Integer> ticks(Integer... values) {
        List<Integer> list = new ArrayList<>();
        Collections.addAll(list, values);
        return list;
    }

    // === New elements ===

    @Test
    @DisplayName("the new @Scheduled elements default to unbound")
    void newScheduledElementsDefaultToUnbound() throws NoSuchMethodException {
        Method method = LiteralBean.class.getDeclaredMethod("tick");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertSame(AbstractConfigEntity.class, scheduled.config());
        assertEquals("", scheduled.periodKey());
        assertEquals("", scheduled.delayKey());
    }

    // === Binding at load ===

    @Nested
    @DisplayName("a bound value is used at load")
    class BoundAtLoad {

        @Test
        @DisplayName("a bound period runs every configured number of seconds, times twenty ticks")
        void boundPeriodRunsEveryConfiguredSecondsTimesTwenty() {
            config.setPeriodSeconds(3);
            BoundPeriodBean bean = new BoundPeriodBean();

            taskManager.registerScheduledMethods(module, bean);
            advanceTo(125);

            assertEquals(ticks(1, 61, 121), bean.fireTicks,
                    "3 seconds must be 60 ticks between runs; the annotation's literal period is -1 (run once)");
            assertEquals(1, taskManager.getTaskCount(module));
        }

        @Test
        @DisplayName("a bound delay holds the first run for the configured number of seconds")
        void boundDelayHoldsTheFirstRunForTheConfiguredSeconds() {
            config.setPeriodSeconds(2);
            BoundDelayAndPeriodBean bean = new BoundDelayAndPeriodBean();

            taskManager.registerScheduledMethods(module, bean);
            advanceTo(85);

            assertEquals(ticks(40, 80), bean.fireTicks,
                    "delay and period are both 2 seconds, so the first run is at tick 40, never at load");
        }

        @Test
        @DisplayName("a bound registration logs its own INFO line naming the key and the resolved seconds")
        void boundRegistrationLogsItsOwnInfoLineNamingKeyAndSeconds() {
            config.setPeriodSeconds(3);

            taskManager.registerScheduledMethods(module, new BoundPeriodBean());

            assertEquals(Collections.singletonList(
                            "[UltiTools-API] Registered config-bound @Scheduled task: BoundPeriodBean.tick "
                                    + "(delay=0, period=60, async=false, config=BindingTimingConfig, "
                                    + "periodKey=timer.period=3s)"),
                    messagesAt(Level.INFO));
        }
    }

    // === Literal path ===

    @Nested
    @DisplayName("literal-only usages are unchanged")
    class LiteralPathUnchanged {

        @Test
        @DisplayName("the literal registration log line is byte-identical to 6.2.x")
        void literalRegistrationLogLineIsByteIdentical() {
            taskManager.registerScheduledMethods(module, new LiteralBean());

            assertEquals(Collections.singletonList(
                            "[UltiTools-API] Registered @Scheduled task: LiteralBean.tick (delay=7, period=20, async=false)"),
                    messagesAt(Level.INFO));
        }

        @Test
        @DisplayName("literal tasks reach the scheduler through the same calls with the same ticks")
        void literalTasksUseTheSameSchedulerCalls() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("TaskManagerConfigBindingTest.literal"));
                BukkitTask task = mock(BukkitTask.class);
                when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                        .thenReturn(task);
                when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenReturn(task);
                when(scheduler.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(),
                        anyLong())).thenReturn(task);

                taskManager.registerScheduledMethods(module, new LiteralBean());
                taskManager.registerScheduledMethods(module, new LiteralOneShotBean());
                taskManager.registerScheduledMethods(module, new LiteralAsyncBean());

                verify(scheduler).runTaskTimer(eq(host), any(Runnable.class), eq(7L), eq(20L));
                verify(scheduler).runTaskLater(eq(host), any(Runnable.class), eq(100L));
                verify(scheduler).runTaskTimerAsynchronously(eq(host), any(Runnable.class), eq(0L), eq(40L));
                assertEquals(3, taskManager.getTaskCount(module));
            }
        }

        @Test
        @DisplayName("a literal registration never reads module configuration")
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // the assertion IS verifyNoInteractions(...)
        void literalRegistrationNeverReadsConfiguration() {
            taskManager.registerScheduledMethods(module, new LiteralBean());

            verifyNoInteractions(configManager);
        }

        @Test
        @DisplayName("a reload leaves a literal task alone")
        void reloadLeavesALiteralTaskAlone() {
            taskManager.registerScheduledMethods(module, new LiteralBean());
            Set<Integer> before = pendingTaskIds();

            advanceTo(30);
            taskManager.rescheduleBound(module);

            assertEquals(before, pendingTaskIds());
            verifyNoInteractions(configManager);
        }
    }

    // === Reload ===

    @Nested
    @DisplayName("/ul reload reschedules a changed bound task and keeps its place in the cycle")
    class ReloadReschedule {

        @Test
        @DisplayName("an unchanged value leaves the running task alone")
        void unchangedValueLeavesTheRunningTaskAlone() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(30);
            Set<Integer> before = pendingTaskIds();
            int infoBefore = messagesAt(Level.INFO).size();

            taskManager.rescheduleBound(module);

            assertEquals(before, pendingTaskIds(), "the same Bukkit task must still be the one scheduled");
            assertEquals(infoBefore, messagesAt(Level.INFO).size(), "an unchanged value logs nothing");
            advanceTo(205);
            assertEquals(ticks(1, 101, 201), bean.fireTicks);
        }

        @Test
        @DisplayName("a longer interval: the next run is the last run plus the new interval")
        void longerIntervalNextRunIsLastRunPlusNewInterval() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(140);
            assertEquals(ticks(1, 101), bean.fireTicks);

            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            advanceTo(300);
            assertEquals(ticks(1, 101), bean.fireTicks,
                    "the old cadence (201) must not run, and a restarted clock would say 340");
            advanceTo(301);
            assertEquals(ticks(1, 101, 301), bean.fireTicks, "last run 101 + 200 ticks");
            advanceTo(501);
            assertEquals(ticks(1, 101, 301, 501), bean.fireTicks, "the new interval repeats");
        }

        @Test
        @DisplayName("a shorter interval not yet overdue: the next run is the last run plus the new interval")
        void shorterIntervalNotYetOverdueNextRunIsLastRunPlusNewInterval() {
            config.setPeriodSeconds(10);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(250);
            assertEquals(ticks(1, 201), bean.fireTicks);

            config.setPeriodSeconds(5);
            taskManager.rescheduleBound(module);

            advanceTo(300);
            assertEquals(ticks(1, 201), bean.fireTicks, "a restarted clock would not run until 350");
            advanceTo(301);
            assertEquals(ticks(1, 201, 301), bean.fireTicks, "last run 201 + 100 ticks");
            advanceTo(401);
            assertEquals(ticks(1, 201, 301, 401), bean.fireTicks);
        }

        @Test
        @DisplayName("a shorter interval already overdue: the task runs on the next tick, then every new interval")
        void shorterIntervalAlreadyOverdueRunsOnTheNextTick() {
            config.setPeriodSeconds(10);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(350);
            assertEquals(ticks(1, 201), bean.fireTicks);

            config.setPeriodSeconds(5);
            taskManager.rescheduleBound(module);

            assertEquals(ticks(1, 201), bean.fireTicks, "the reload itself runs nothing");
            advanceTo(351);
            assertEquals(ticks(1, 201, 351), bean.fireTicks, "last run 201 + 100 = 301 has passed");
            advanceTo(451);
            assertEquals(ticks(1, 201, 351, 451), bean.fireTicks);
        }

        @Test
        @DisplayName("repeated reloads never postpone the next run")
        void repeatedReloadsNeverPostponeTheNextRun() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(150);
            assertEquals(ticks(1, 101), bean.fireTicks);

            config.setPeriodSeconds(6);
            taskManager.rescheduleBound(module);
            advanceTo(200);
            config.setPeriodSeconds(5);
            taskManager.rescheduleBound(module);

            advanceTo(201);
            assertEquals(ticks(1, 101, 201), bean.fireTicks,
                    "anchored on the last run (101 + 100); a clock restarted at each reload would say 300");
        }

        @Test
        @DisplayName("before the first run, a reload anchors on the arm tick and never runs the task early")
        void beforeTheFirstRunAReloadAnchorsOnTheArmTick() {
            config.setPeriodSeconds(10);
            BoundDelayAndPeriodBean bean = new BoundDelayAndPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(100);

            config.setPeriodSeconds(20);
            taskManager.rescheduleBound(module);

            advanceTo(399);
            assertTrue(bean.fireTicks.isEmpty(),
                    "nothing may run before arm tick 0 + 400 -- not at the reload, not at the old 200");
            advanceTo(400);
            assertEquals(ticks(400), bean.fireTicks, "arm tick 0 + 20 s; a restarted clock would say 500");
            advanceTo(800);
            assertEquals(ticks(400, 800), bean.fireTicks);
        }

        @Test
        @DisplayName("a changed value logs one INFO line naming the task and the new timing")
        void aChangedValueLogsOneInfoLine() {
            config.setPeriodSeconds(5);
            taskManager.registerScheduledMethods(module, new BoundPeriodBean());
            advanceTo(140);
            logs.clear();

            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            List<String> info = messagesAt(Level.INFO);
            assertEquals(1, info.size(), info.toString());
            assertTrue(info.get(0).contains("BoundPeriodBean.tick"), info.get(0));
            assertTrue(info.get(0).contains("period=200"), info.get(0));
            assertTrue(info.get(0).contains("next run in 161 ticks"), info.get(0));
        }

        @Test
        @DisplayName("cancelAll after a reschedule cancels the replacement task")
        void cancelAllAfterARescheduleCancelsTheReplacementTask() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(140);
            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            taskManager.cancelAll(module);

            assertEquals(0, taskManager.getTaskCount(module));
            assertTrue(pendingTaskIds().isEmpty(), "no task of this module may stay scheduled");
            advanceTo(600);
            assertEquals(ticks(1, 101), bean.fireTicks);
        }

        @Test
        @DisplayName("a reload requested off the main thread is refused before any task is touched")
        void aReloadOffTheMainThreadIsRefusedBeforeAnyTaskIsTouched() {
            config.setPeriodSeconds(5);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("TaskManagerConfigBindingTest.thread"));
                BukkitTask first = mock(BukkitTask.class);
                when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                        .thenReturn(first);
                taskManager.registerScheduledMethods(module, new BoundPeriodBean());

                config.setPeriodSeconds(10);
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> taskManager.rescheduleBound(module));

                assertTrue(refused.getMessage().contains("main thread"), refused.getMessage());
                verify(first, never()).cancel();
                verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
                verify(scheduler).runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
            }
        }

        @Test
        @DisplayName("a value above the ceiling on reload keeps the running period and warns")
        void aValueAboveTheCeilingOnReloadKeepsTheRunningPeriod() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);
            Set<Integer> before = pendingTaskIds();

            config.setPeriodSeconds(Integer.MAX_VALUE / 20 + 1);
            taskManager.rescheduleBound(module);

            assertEquals(before, pendingTaskIds());
            assertEquals(1, messagesAt(Level.WARNING).size());
            advanceTo(201);
            assertEquals(ticks(1, 101, 201), bean.fireTicks);
        }
    }

    // === IN-07 gaps ===

    @Nested
    @DisplayName("proxied beans and delay-only one-shots")
    class ProxiesAndOneShots {

        @Test
        @DisplayName("a bound method on a ByteBuddy-proxied bean is scheduled and runs through the proxy")
        void boundMethodOnAProxiedBeanRuns() throws Exception {
            config.setPeriodSeconds(2);
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());
            Set<Method> intercepted = new LinkedHashSet<>(Collections.singletonList(
                    BoundPeriodBean.class.getMethod("tick")));
            BoundPeriodBean proxy = proxyFactory.createProxyClass(BoundPeriodBean.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            taskManager.registerScheduledMethods(module, proxy);
            advanceTo(45);

            assertEquals(1, taskManager.getTaskCount(module));
            assertEquals(ticks(1, 41), proxy.fireTicks);
        }

        @Test
        @DisplayName("a delay-only bound one-shot moved by a reload before it ran runs once, at arm tick + new delay")
        void delayOnlyOneShotReloadBeforeItRan() {
            config.setPeriodSeconds(5);
            BoundDelayOnlyBean bean = new BoundDelayOnlyBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);

            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            advanceTo(199);
            assertTrue(bean.fireTicks.isEmpty(), "not at the old 100, not early");
            advanceTo(400);
            assertEquals(ticks(200), bean.fireTicks, "arm tick 0 + 10 s, exactly once");
            assertTrue(pendingTaskIds().isEmpty());
        }

        @Test
        @DisplayName("a delay-only bound one-shot that already ran is not re-armed by a reload")
        void delayOnlyOneShotReloadAfterItRan() {
            config.setPeriodSeconds(5);
            BoundDelayOnlyBean bean = new BoundDelayOnlyBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(150);
            assertEquals(ticks(100), bean.fireTicks);

            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            assertTrue(pendingTaskIds().isEmpty(), "a one-shot that ran must not be scheduled again");
            advanceTo(600);
            assertEquals(ticks(100), bean.fireTicks);
        }
    }

    // === Invalid on reload ===

    @Nested
    @DisplayName("an invalid value on reload keeps the running value and warns")
    class InvalidOnReload {

        @Test
        @DisplayName("zero keeps the running period and logs a WARNING naming module, key, value and kept value")
        void zeroKeepsTheRunningPeriodAndWarns() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);
            Set<Integer> before = pendingTaskIds();

            config.setPeriodSeconds(0);
            taskManager.rescheduleBound(module);

            assertEquals(before, pendingTaskIds(), "the running task must not be replaced");
            List<String> warnings = messagesAt(Level.WARNING);
            assertEquals(1, warnings.size(), warnings.toString());
            String warning = warnings.get(0);
            assertTrue(warning.contains("TimingModule"), warning);
            assertTrue(warning.contains("timer.period"), warning);
            assertTrue(warning.contains("value 0"), warning);
            assertTrue(warning.contains("keeping 5s"), warning);
            advanceTo(201);
            assertEquals(ticks(1, 101, 201), bean.fireTicks, "0 does not mean off; the task keeps running");
        }

        @Test
        @DisplayName("a negative value keeps the running period")
        void negativeKeepsTheRunningPeriod() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);

            config.setPeriodSeconds(-3);
            taskManager.rescheduleBound(module);

            assertEquals(1, messagesAt(Level.WARNING).size());
            advanceTo(201);
            assertEquals(ticks(1, 101, 201), bean.fireTicks);
        }

        @Test
        @DisplayName("a null boxed value keeps the running period")
        void nullBoxedValueKeepsTheRunningPeriod() {
            config.setBoxedSeconds(5);
            BoundBoxedBean bean = new BoundBoxedBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);

            config.setBoxedSeconds(null);
            taskManager.rescheduleBound(module);

            List<String> warnings = messagesAt(Level.WARNING);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("value null"), warnings.get(0));
            advanceTo(201);
            assertEquals(ticks(1, 101, 201), bean.fireTicks);
        }

        @Test
        @DisplayName("a later valid value is applied again after an invalid one was kept out")
        void aLaterValidValueIsAppliedAgain() {
            config.setPeriodSeconds(5);
            BoundPeriodBean bean = new BoundPeriodBean();
            taskManager.registerScheduledMethods(module, bean);
            advanceTo(50);
            config.setPeriodSeconds(0);
            taskManager.rescheduleBound(module);

            advanceTo(140);
            config.setPeriodSeconds(10);
            taskManager.rescheduleBound(module);

            advanceTo(301);
            assertEquals(ticks(1, 101, 301), bean.fireTicks);
        }
    }

    // === Outside modules ===

    @Nested
    @DisplayName("a binding outside a module is refused")
    class OutsideModules {

        /**
         * Round 2 of gate-1 WR-01 (orchestrator ruling): a config-bound {@code @Scheduled} cannot be
         * {@code async}. Keeping an async task's phase across a reload needs either a prediction of
         * the server's scheduler clock (which proved wrong for tasks armed at boot) or a sync trigger
         * that dispatches the work -- the capability is withdrawn instead, and the author binds a sync
         * task and dispatches heavy work to the async scheduler themselves.
         */
        @Test
        @DisplayName("a bound async @Scheduled is refused when scheduled, naming the method, and nothing is scheduled")
        void aBoundAsyncMethodIsRefusedAndNothingIsScheduled() {
            PluginModuleException refused = assertThrows(PluginModuleException.class,
                    () -> taskManager.registerScheduledMethods(module, new BoundAsyncBean()));

            assertTrue(refused.getMessage().contains("BoundAsyncBean.tick"), refused.getMessage());
            assertTrue(refused.getMessage().contains("async"), refused.getMessage());
            assertTrue(refused.getMessage().contains("runTaskAsynchronously"), refused.getMessage());
            assertEquals(0, taskManager.getTaskCount(module));
            assertTrue(pendingTaskIds().isEmpty());
        }

        @Test
        @DisplayName("a bound async @Scheduled with only periodKey is refused too")
        void aBoundAsyncPeriodOnlyMethodIsRefused() {
            assertThrows(PluginModuleException.class,
                    () -> taskManager.registerScheduledMethods(module, new BoundAsyncPeriodOnlyBean()));

            assertEquals(0, taskManager.getTaskCount(module));
        }

        @Test
        @DisplayName("a literal async @Scheduled is untouched")
        void aLiteralAsyncMethodStillSchedules() {
            taskManager.registerScheduledMethods(module, new LiteralAsyncBean());

            assertEquals(1, taskManager.getTaskCount(module));
        }

        @Test
        @DisplayName("a bound method on an external plugin's bean is refused and nothing is scheduled")
        void boundMethodOnAnExternalBeanIsRefused() {
            PluginModuleException refused = assertThrows(PluginModuleException.class,
                    () -> taskManager.registerScheduledMethodsExternal("ExternalPlugin", new BoundPeriodBean()));

            assertTrue(refused.getMessage().contains("BoundPeriodBean.tick"), refused.getMessage());
            assertEquals(0, taskManager.getExternalTaskCount("ExternalPlugin"));
            assertTrue(pendingTaskIds().isEmpty());
        }

        @Test
        @DisplayName("a bound method on a framework-owned object is refused and nothing is scheduled")
        void boundMethodOnACoreBeanIsRefused() {
            assertThrows(PluginModuleException.class,
                    () -> taskManager.registerScheduledMethodsCore(new BoundPeriodBean()));

            assertEquals(0, taskManager.getCoreTaskCount());
            assertTrue(pendingTaskIds().isEmpty(), "nothing may be scheduled");
        }
    }
}

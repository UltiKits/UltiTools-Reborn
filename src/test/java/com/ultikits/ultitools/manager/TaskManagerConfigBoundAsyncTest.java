package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.testutil.BindingTimingConfig;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #535: control the order within a heartbeat, including the boot clock offset, and count live
 * scheduler tasks rather than registration calls. Worker bodies really run on a separate thread;
 * this probe is not a claim about real Paper dispatch latency (which still needs UAT).
 */
@DisplayName("Config-bound async @Scheduled observation and dispatch (#535)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TaskManagerConfigBoundAsyncTest {
    private JavaPlugin host;
    private UltiToolsPlugin module;
    private BindingTimingConfig config;
    private TaskManager manager;

    public static class AsyncBean {
        final List<Thread> bodyThreads = new ArrayList<>();
        boolean fail;

        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", async = true)
        public void tick() {
            bodyThreads.add(Thread.currentThread());
            if (fail) {
                throw new IllegalStateException("scheduled body failure");
            }
        }
    }

    public static class BlockingAsyncBean extends AsyncBean {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean interrupted;

        @Override
        @Scheduled(config = BindingTimingConfig.class, periodKey = "timer.period", async = true)
        public void tick() {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("worker was not released");
                }
                super.tick();
            } catch (InterruptedException failure) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
        }
    }

    public static class AsyncOnceBean extends AsyncBean {
        @Override
        @Scheduled(config = BindingTimingConfig.class, delayKey = "timer.delay", async = true)
        public void tick() {
            super.tick();
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        host = MockBukkit.createMockPlugin();
        module = mock(UltiToolsPlugin.class);
        lenient().when(module.getPluginName()).thenReturn("AsyncTimingModule");
        config = new BindingTimingConfig();
        ConfigManager configs = mock(ConfigManager.class);
        when(configs.getConfigEntities(module, BindingTimingConfig.class))
                .thenReturn(Collections.singletonList(config));
        TestHelper.mockUltiToolsInstance(framework -> when(framework.getConfigManager()).thenReturn(configs));
        manager = new TaskManager(host);
    }

    @AfterEach
    void tearDown() {
        manager.cancelAll(module);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void bootTriggerFiresAtArmTickAndBodyRunsOnWorkerOneHeartbeatLater() throws Exception {
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.beginTick(0);
            probe.runTriggers();

            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveWorkers()).hasSize(1);
            assertThat(probe.liveWorkers().get(0).due).isEqualTo(1);
            probe.runWorkers();
            assertThat(bean.bodyThreads).isEmpty();

            probe.advanceTo(1);
            assertThat(bean.bodyThreads).hasSize(1);
            assertThat(bean.bodyThreads.get(0)).isNotSameAs(Thread.currentThread());
            assertThat(probe.liveWorkers()).isEmpty();
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(100);
        }
    }

    @Test
    void reloadAfterBootTriggerUsesObservedTickBeforeTheWorkerStarts() throws Exception {
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.beginTick(0);
            probe.runTriggers();
            config.setPeriodSeconds(6);
            manager.rescheduleBound(module);

            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(120);
            assertThat(probe.liveWorkers()).hasSize(1);
            probe.advanceTo(119);
            assertThat(bean.bodyThreads).hasSize(1);
            probe.advanceTo(120);
            assertThat(probe.liveWorkers()).hasSize(1);
            assertThat(probe.liveTriggers()).hasSize(1);
            probe.advanceTo(121);
            assertThat(bean.bodyThreads).hasSize(2);
        }
    }

    @Test
    void reloadBeforeBootTriggerCancelsItAndRearmsForOneTick() throws Exception {
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.beginTick(0);
            config.setPeriodSeconds(6);
            manager.rescheduleBound(module);
            probe.runTriggers();

            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(1);
            assertThat(probe.liveWorkers()).isEmpty();
            probe.advanceTo(2);
            assertThat(bean.bodyThreads).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(121);
        }
    }

    @Test
    void reloadBeforeDueTriggerKeepsPhaseAndNeverLeavesTwoLiveTriggers() throws Exception {
        config.setPeriodSeconds(1);
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(19);
            probe.beginTick(20);
            config.setPeriodSeconds(2);
            manager.rescheduleBound(module);
            probe.runTriggers();

            assertThat(probe.liveWorkers()).isEmpty();
            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(40);
            config.setPeriodSeconds(3);
            manager.rescheduleBound(module);
            config.setPeriodSeconds(2);
            manager.rescheduleBound(module);
            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(40);
            probe.advanceTo(41);
            assertThat(bean.bodyThreads).hasSize(2);
            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveWorkers()).isEmpty();
        }
    }

    @Test
    void reloadAfterDueTriggerPreservesItsQueuedWorkAndUsesItsNewStamp() throws Exception {
        config.setPeriodSeconds(1);
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(20);
            config.setPeriodSeconds(2);
            manager.rescheduleBound(module);

            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(60);
            assertThat(probe.liveWorkers()).hasSize(1);
            probe.advanceTo(21);
            assertThat(bean.bodyThreads).hasSize(2);
            probe.advanceTo(61);
            assertThat(bean.bodyThreads).hasSize(3);
        }
    }

    @Test
    void unchangedAndInvalidReloadsLeaveTriggerAndQueuedWorkerAlone() throws Exception {
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(0);
            List<Work> before = new ArrayList<>(probe.liveTriggers());
            List<Work> workers = new ArrayList<>(probe.liveWorkers());
            manager.rescheduleBound(module);
            config.setPeriodSeconds(0);
            ReloadReport report = new ReloadReport();
            manager.rescheduleBound(module, report);

            assertThat(probe.liveTriggers()).containsExactlyElementsOf(before);
            assertThat(probe.liveWorkers()).containsExactlyElementsOf(workers);
            assertThat(report.isPartial()).isTrue();
            probe.advanceTo(101);
            assertThat(bean.bodyThreads).hasSize(2);
        }
    }

    @Test
    void delayOnlyAsyncOneShotKeepsArmAnchorAndNeverRearmsAfterTrigger() throws Exception {
        config.setDelaySeconds(1);
        AsyncOnceBean bean = new AsyncOnceBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(10);
            config.setDelaySeconds(2);
            manager.rescheduleBound(module);
            assertThat(probe.liveTriggers()).hasSize(1);
            assertThat(probe.liveTriggers().get(0).due).isEqualTo(40);
            probe.advanceTo(40);
            config.setDelaySeconds(3);
            manager.rescheduleBound(module);
            assertThat(probe.liveTriggers()).isEmpty();
            assertThat(probe.liveWorkers()).hasSize(1);
            probe.advanceTo(100);
            assertThat(bean.bodyThreads).hasSize(1);
            assertThat(probe.liveWorkers()).isEmpty();
        }
    }

    @Test
    void unloadCancelsTriggerAndQueuedWorker() throws Exception {
        AsyncBean bean = new AsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(0);
            assertThat(probe.liveWorkers()).hasSize(1);
            manager.cancelAll(module);

            assertThat(manager.getTaskCount(module)).isZero();
            assertThat(probe.liveTriggers()).isEmpty();
            assertThat(probe.liveWorkers()).isEmpty();
            probe.advanceTo(201);
            assertThat(bean.bodyThreads).isEmpty();
        }
    }

    @Test
    void unloadAfterOneShotTriggerStillCancelsItsQueuedWorker() throws Exception {
        config.setDelaySeconds(1);
        AsyncOnceBean bean = new AsyncOnceBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(19);
            assertThat(probe.liveTriggers()).isEmpty();
            assertThat(probe.liveWorkers()).hasSize(1);
            manager.cancelAll(module);
            assertThat(probe.liveWorkers()).isEmpty();
            probe.advanceTo(40);
            assertThat(bean.bodyThreads).isEmpty();
        }
    }

    @Test
    void unloadWhileWorkerRunsLetsThatBodyFinishButCancelsFutureFires() throws Exception {
        BlockingAsyncBean bean = new BlockingAsyncBean();
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(0);
            Work dispatched = probe.liveWorkers().get(0);
            probe.beginTick(1);
            Future<?> running = probe.worker.submit(dispatched.runnable);
            try {
                assertThat(bean.started.await(5, TimeUnit.SECONDS)).isTrue();
                manager.cancelAll(module);
                assertThat(dispatched.cancelled).isTrue();
                assertThat(probe.liveTriggers()).isEmpty();
            } finally {
                bean.release.countDown();
                running.get(5, TimeUnit.SECONDS);
            }
            assertThat(bean.interrupted).isFalse();
            assertThat(bean.bodyThreads).hasSize(1);
            probe.advanceTo(201);
            assertThat(bean.bodyThreads).hasSize(1);
            assertThat(probe.liveWorkers()).isEmpty();
        }
    }

    @Test
    void failingAsyncBodyIsLoggedAndDoesNotStopFutureTriggers() throws Exception {
        config.setPeriodSeconds(1);
        AsyncBean bean = new AsyncBean();
        bean.fail = true;
        List<LogRecord> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Bukkit.getLogger().addHandler(capture);
        try (SchedulerProbe probe = new SchedulerProbe()) {
            manager.registerScheduledMethods(module, bean);
            probe.advanceTo(21);
            assertThat(bean.bodyThreads).hasSize(2);
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0).getMessage()).contains("AsyncBean.tick");
            assertThat(warnings.get(0).getThrown()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(probe.liveWorkers()).isEmpty();
            assertThat(probe.liveTriggers()).hasSize(1);
        } finally {
            Bukkit.getLogger().removeHandler(capture);
        }
    }

    private static final class Work {
        final BukkitTask task = mock(BukkitTask.class);
        final Runnable runnable;
        final boolean sync;
        final long period;
        long due;
        boolean cancelled;
        boolean complete;

        Work(Runnable runnable, boolean sync, long due, long period, int id) {
            this.runnable = runnable;
            this.sync = sync;
            this.due = due;
            this.period = period;
            when(task.getTaskId()).thenReturn(id);
            when(task.isSync()).thenReturn(sync);
            when(task.isCancelled()).thenAnswer(call -> cancelled);
            doAnswer(call -> { cancelled = true; return null; }).when(task).cancel();
        }
    }

    /** Explicit queue state, not a replica of Paper's scheduler implementation. */
    private final class SchedulerProbe implements AutoCloseable {
        final List<Work> queue = new ArrayList<>();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final MockedStatic<Bukkit> bukkit;
        int publicTick;
        int schedulerTick = -1;

        SchedulerProbe() {
            java.util.logging.Logger logger = Bukkit.getLogger();
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> publicTick);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                    .thenAnswer(call -> add(call.getArgument(1), true, call.getArgument(2), call.getArgument(3)));
            when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong()))
                    .thenAnswer(call -> add(call.getArgument(1), true, call.getArgument(2), -1));
            when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                    .thenAnswer(call -> add(call.getArgument(1), false, call.getArgument(2), -1));
            when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                    .thenAnswer(call -> add(call.getArgument(1), false, 0, -1));
            doAnswer(call -> {
                int id = call.getArgument(0);
                queue.stream().filter(work -> work.task.getTaskId() == id)
                        .forEach(work -> work.cancelled = true);
                return null;
            }).when(scheduler).cancelTask(anyInt());
        }

        BukkitTask add(Runnable runnable, boolean sync, long delay, long period) {
            Work work = new Work(runnable, sync, schedulerTick + delay, period, queue.size() + 1);
            queue.add(work);
            return work.task;
        }

        void beginTick(int tick) {
            publicTick = tick;
            schedulerTick = tick;
        }

        List<Work> liveTriggers() { return live(true); }
        List<Work> liveWorkers() { return live(false); }

        List<Work> live(boolean sync) {
            List<Work> live = new ArrayList<>();
            for (Work work : queue) {
                if (work.sync == sync && !work.cancelled && !work.complete) {
                    live.add(work);
                }
            }
            return live;
        }

        void runTriggers() {
            for (Work work : liveTriggers()) {
                if (work.due <= schedulerTick) {
                    work.complete = work.period <= 0;
                    work.due = schedulerTick + work.period;
                    work.runnable.run();
                }
            }
        }

        void runWorkers() throws Exception {
            for (Work work : liveWorkers()) {
                if (work.due <= schedulerTick) {
                    worker.submit(work.runnable).get(5, TimeUnit.SECONDS);
                    work.complete = true;
                }
            }
        }

        void advanceTo(int tick) throws Exception {
            while (schedulerTick < tick) {
                beginTick(schedulerTick + 1);
                runTriggers();
                runWorkers();
            }
        }

        @Override
        public void close() {
            manager.cancelAll(module);
            worker.shutdownNow();
            bukkit.close();
        }
    }
}

package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.AbstractSchedulerContractTest;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.Ticks;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

@Timeout(30)
class PaperSchedulerEngineContractTest extends AbstractSchedulerContractTest {
    @Override
    protected Fixture createFixture() {
        ServerMock server = MockBukkit.getMock();
        BukkitSchedulerMock scheduler = server.getScheduler();
        AsyncDeliveries deliveries = AsyncDeliveries.install(scheduler);
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // Records are captured synchronously in memory.
            }

            @Override
            public void close() {
                // No external resources are held.
            }
        });
        RetiringBackend backend = new RetiringBackend(PaperBackend.live(MockBukkit.createMockPlugin()));
        PaperSchedulerEngine engine = new PaperSchedulerEngine(server.getPluginManager().getPlugins()[0], logger, backend);
        Map<String, Scheduler> owners = new HashMap<>();
        return new Fixture() {
            @Override
            public Scheduler scheduler(String owner) {
                return owners.computeIfAbsent(owner, engine::newOwner);
            }

            @Override
            public void advanceTicks(long ticks) {
                scheduler.performTicks(ticks);
            }

            @Override
            public void elapseAsync(Duration duration) {
                advanceTicks(Ticks.ceil(duration) + 1);
                deliveries.awaitThroughTick(scheduler.getCurrentTick());
            }

            @Override
            public void retire(Entity entity) {
                backend.retire(entity);
            }

            @Override
            public void unload(String owner) {
                engine.retire(owners.get(owner));
            }

            @Override
            public List<LogRecord> warnings() {
                return new ArrayList<>(warnings);
            }

            @Override
            public void close() {
                owners.values().forEach(engine::retire);
            }
        };
    }

    /** Records the futures of actual MockBukkit deliveries without changing which tasks the ticks dispatch. */
    private static final class AsyncDeliveries extends ThreadPoolExecutor {
        // MockBukkit submits these deliveries on the tick thread, which also drains this queue.
        private final Queue<Future<?>> dispatched = new ArrayDeque<>();

        private AsyncDeliveries(ThreadPoolExecutor original) {
            super(original.getCorePoolSize(), original.getMaximumPoolSize(),
                    original.getKeepAliveTime(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS,
                    original.getQueue(), original.getThreadFactory(), original.getRejectedExecutionHandler());
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        static AsyncDeliveries install(BukkitSchedulerMock scheduler) {
            try {
                // MockBukkit has no executor injection point. Replace only the fresh fixture's dispatch pool.
                Field pool = BukkitSchedulerMock.class.getDeclaredField("pool");
                pool.setAccessible(true);
                ThreadPoolExecutor original = (ThreadPoolExecutor) pool.get(scheduler);
                AsyncDeliveries tracking = new AsyncDeliveries(original);
                pool.set(scheduler, tracking);
                original.shutdown();
                return tracking;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot track MockBukkit's async deliveries", failure);
            }
        }

        @Override
        public Future<?> submit(Runnable task) {
            Future<?> completion = super.submit(task);
            dispatched.add(completion);
            return completion;
        }

        void awaitThroughTick(long tick) {
            // waitAsyncTasksFinished() can see zero active workers after a hand-off but before a worker
            // starts the callback. Only the returned Future confirms the callback has actually finished.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); // Deadlock guard only.
            for (Future<?> completion; (completion = dispatched.poll()) != null; ) {
                try {
                    completion.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException failure) {
                    throw new AssertionError("Timed out waiting for MockBukkit async deliveries through tick " + tick,
                            failure);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for MockBukkit async deliveries", interrupted);
                } catch (ExecutionException failure) {
                    throw new AssertionError("MockBukkit async delivery failed", failure.getCause());
                }
            }
        }
    }
}

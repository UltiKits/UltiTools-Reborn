package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.AbstractSchedulerContractTest;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.Ticks;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
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
            } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException
                     | InaccessibleObjectException | SecurityException failure) {
                throw new AssertionError("MockBukkit's async pool layout changed or the JDK denied replacing it; "
                        + "update the Paper scheduler contract fixture to track deliveries on this MockBukkit/JDK version",
                        failure);
            }
        }

        @Override
        public void execute(Runnable task) {
            // submit already supplies a Future; keep it so its exceptions remain visible to the fixture.
            RunnableFuture<?> completion = task instanceof RunnableFuture<?> future
                    ? future : new FutureTask<>(task, null);
            super.execute(completion);
            dispatched.add(completion);
        }

        void awaitThroughTick(long tick) {
            // An idle pool need not have started the hand-off. Wait for the actual deliveries, with
            // enough room for three drains before the outer JUnit watchdog.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            for (Future<?> completion; (completion = dispatched.peek()) != null; ) {
                try {
                    completion.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    dispatched.remove();
                } catch (TimeoutException failure) {
                    long unfinished = dispatched.stream().filter(task -> !task.isDone()).count();
                    throw new AssertionError("Timed out waiting for MockBukkit async deliveries through tick " + tick
                            + " (" + unfinished + " unfinished)", failure);
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

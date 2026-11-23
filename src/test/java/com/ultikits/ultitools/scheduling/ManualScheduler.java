package com.ultikits.ultitools.scheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/** Test-only scheduler with a shared clock, explicit entity retirement and per-owner unload. */
public final class ManualScheduler implements Scheduler {
    private static final long TICK_NANOS = 50_000_000L;
    private final Engine engine;
    private final String owner;
    private final Context global;
    private final Context async;
    private boolean unloaded;

    private ManualScheduler(Engine engine, String owner) {
        this.engine = engine;
        this.owner = owner;
        global = new Context(false, null);
        async = new Context(true, null);
    }

    @Override public String owner() { return owner; }
    @Override public TaskContext global() { return global; }

    @Override
    public TaskContext region(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location has no world");
        }
        return region(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    @Override
    public TaskContext region(World world, int chunkX, int chunkZ) {
        Objects.requireNonNull(world, "world");
        return new Context(false, null);
    }

    @Override
    public TaskContext entity(Entity entity) {
        return new Context(false, Objects.requireNonNull(entity, "entity"));
    }

    @Override public TaskContext async() { return async; }

    private void requireLoaded() {
        if (unloaded) {
            throw new RejectedExecutionException("Owner unloaded: " + owner);
        }
    }

    /** Test clock and lifecycle adapter shared by every owner in one contract fixture. */
    public static final class Engine implements AbstractSchedulerContractTest.Fixture {
        private final Map<String, ManualScheduler> owners = new HashMap<>();
        private final List<ManualTask> tasks = new ArrayList<>();
        private final Set<Entity> retired = new HashSet<>();
        private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        private final Thread tickThread = Thread.currentThread();
        private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "manual-async");
            thread.setDaemon(true);
            return thread;
        });
        private long now;
        private long sequence;

        @Override
        public synchronized Scheduler scheduler(String owner) {
            return owners.computeIfAbsent(owner, name -> new ManualScheduler(this, name));
        }

        @Override public void advanceTicks(long ticks) { advance(Ticks.of(ticks)); }
        @Override public void elapseAsync(Duration atLeast) { advance(atLeast); }

        private void advance(Duration elapsed) {
            if (Thread.currentThread() != tickThread) {
                throw new IllegalStateException("Clock advances must run on the tick thread");
            }
            if (elapsed.isNegative()) {
                throw new IllegalArgumentException("elapsed must not be negative");
            }
            long target;
            synchronized (this) {
                target = Math.addExact(now, elapsed.toNanos());
            }
            try {
                while (true) {
                    ManualTask next;
                    synchronized (this) {
                        next = tasks.stream().filter(task -> task.state == TaskState.SCHEDULED && task.due <= target)
                                .min(Comparator.comparingLong((ManualTask task) -> task.due)
                                        .thenComparingLong(task -> task.sequence)).orElse(null);
                        if (next == null) {
                            now = target;
                            return;
                        }
                        now = next.due;
                        if (next.context.entity != null && retired.contains(next.context.entity)) {
                            next.retire();
                            continue;
                        }
                        next.state = TaskState.RUNNING;
                    }
                    if (next.context.asynchronous) {
                        try {
                            asyncExecutor.submit(() -> executeTask(next)).get();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while settling async tasks", interrupted);
                        } catch (ExecutionException failed) {
                            throw new AssertionError("Async test executor failed", failed.getCause());
                        }
                    } else {
                        executeTask(next);
                    }
                }
            } finally {
                synchronized (this) {
                    tasks.removeIf(task -> task.state != TaskState.SCHEDULED && task.state != TaskState.RUNNING);
                }
            }
        }

        private void executeTask(ManualTask task) {
            Throwable failure = null;
            try {
                task.action.accept(task);
            } catch (Throwable thrown) {
                failure = thrown;
            }
            synchronized (this) {
                if (task.period > 0 && failure != null) {
                    warn("Repeating task failed for owner {0}", task.context.ownerName(), failure);
                }
                if (task.state != TaskState.RUNNING) {
                    return;
                }
                if (task.period > 0) {
                    task.due = Math.addExact(now, task.period);
                    task.state = TaskState.SCHEDULED;
                } else if (failure == null) {
                    task.state = TaskState.COMPLETED;
                    task.completion.complete(null);
                } else {
                    task.state = TaskState.FAILED;
                    task.completion.completeExceptionally(failure);
                }
            }
        }

        private void warn(String pattern, String owner, Throwable thrown) {
            LogRecord record = new LogRecord(Level.WARNING, pattern);
            record.setParameters(new Object[] {owner});
            record.setThrown(thrown);
            warnings.add(record);
        }

        @Override
        public synchronized void retire(Entity entity) {
            retired.add(entity);
            for (ManualTask task : tasks) {
                if (task.context.entity == entity) {
                    task.retire();
                }
            }
        }

        @Override
        public synchronized void unload(String owner) {
            ManualScheduler scheduler = owners.get(owner);
            if (scheduler != null) {
                scheduler.unloaded = true;
                for (ManualTask task : tasks) {
                    if (task.context.ownerName().equals(owner)) {
                        task.cancel();
                    }
                }
            }
        }

        @Override public List<LogRecord> warnings() { return List.copyOf(warnings); }

        @Override
        public void close() {
            synchronized (this) {
                List.copyOf(owners.keySet()).forEach(this::unload);
                tasks.clear();
            }
            asyncExecutor.shutdown();
        }
    }

    private final class Context implements TaskContext {
        private final boolean asynchronous;
        private final Entity entity;

        private Context(boolean asynchronous, Entity entity) {
            this.asynchronous = asynchronous;
            this.entity = entity;
        }

        private String ownerName() { return owner; }

        @Override
        public TaskHandle run(Runnable task) {
            Objects.requireNonNull(task, "task");
            return runLater(Duration.ZERO, task);
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(task, "task");
            return submit(delay, Duration.ZERO, ignored -> task.run());
        }

        @Override
        public TaskHandle runRepeating(Duration initialDelay, Duration period, Consumer<? super TaskHandle> task) {
            Objects.requireNonNull(initialDelay, "initialDelay");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(task, "task");
            if (period.isNegative() || period.isZero() || (!asynchronous && period.compareTo(Ticks.TICK) < 0)) {
                throw new IllegalArgumentException("Invalid period");
            }
            return submit(initialDelay, period, task);
        }

        private ManualTask submit(Duration delay, Duration period, Consumer<? super TaskHandle> task) {
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
            synchronized (engine) {
                requireLoaded();
                long delayNanos = asynchronous ? delay.toNanos()
                        : Math.multiplyExact(Math.max(1, Ticks.ceil(delay)), TICK_NANOS);
                long periodNanos = period.isZero() ? 0 : asynchronous ? period.toNanos()
                        : Math.multiplyExact(Ticks.ceil(period), TICK_NANOS);
                ManualTask handle = new ManualTask(engine, this, task, Math.addExact(engine.now, delayNanos),
                        periodNanos, engine.sequence++);
                engine.tasks.add(handle);
                if (entity != null && engine.retired.contains(entity)) {
                    handle.retire();
                }
                return handle;
            }
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<? extends T> task) {
            Objects.requireNonNull(task, "task");
            CompletableFuture<T> result = new CompletableFuture<>();
            ManualTask handle = submit(Duration.ZERO, Duration.ZERO, ignored -> {
                if (!result.isCancelled()) {
                    try {
                        result.complete(task.get());
                    } catch (Throwable thrown) {
                        if (result.isCancelled()) {
                            engine.warn("Cancelled supplier failed for owner {0}", owner, thrown);
                        }
                        throw thrown;
                    }
                }
            });
            handle.completion.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                }
            });
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) {
                    handle.cancel();
                }
            });
            return result;
        }

        @Override
        public boolean isCurrentThread() {
            return !asynchronous && Thread.currentThread() == engine.tickThread;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            synchronized (engine) {
                requireLoaded();
                if (entity != null && engine.retired.contains(entity)) {
                    throw new RejectedExecutionException("Entity already removed");
                }
                run(command);
            }
        }
    }

    private static final class ManualTask implements TaskHandle {
        private final Engine engine;
        private final Context context;
        private final Consumer<? super TaskHandle> action;
        private final long period;
        private final long sequence;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private long due;
        private TaskState state = TaskState.SCHEDULED;

        private ManualTask(Engine engine, Context context, Consumer<? super TaskHandle> action,
                long due, long period, long sequence) {
            this.engine = engine;
            this.context = context;
            this.action = action;
            this.due = due;
            this.period = period;
            this.sequence = sequence;
        }

        @Override public TaskState state() { synchronized (engine) { return state; } }
        @Override public boolean isRepeating() { return period > 0; }

        @Override
        public boolean cancel() {
            synchronized (engine) {
                if ((state != TaskState.SCHEDULED && state != TaskState.RUNNING)
                        || (state == TaskState.RUNNING && !isRepeating())) {
                    return false;
                }
                state = TaskState.CANCELLED;
                completion.completeExceptionally(new CancellationException("Owner: " + context.ownerName()));
                return true;
            }
        }

        private void retire() {
            synchronized (engine) {
                if (state == TaskState.SCHEDULED || (state == TaskState.RUNNING && isRepeating())) {
                    state = TaskState.RETIRED;
                    completion.completeExceptionally(new TaskRetiredException("Entity removed for " + context.ownerName()));
                }
            }
        }

        @Override public CompletionStage<Void> completion() { return completion.minimalCompletionStage(); }
    }
}

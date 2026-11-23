package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Scheduler} decorator owned by the UI contract suite. Every task submitted through it runs with a
 * thread-local record of the context it was submitted to, so the suite can tell an entity task from a
 * global task without asking the implementation under test. Works over any scheduler (ManualScheduler, a
 * MockBukkit-backed Paper scheduler).
 */
public final class RecordingScheduler implements Scheduler {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Scheduler delegate;

    public record Scope(Kind kind, @Nullable UUID entity) {
        public enum Kind { GLOBAL, REGION, ENTITY, ASYNC }
    }

    public RecordingScheduler(Scheduler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The scope of the task running on the calling thread; empty outside any task submitted through a RecordingScheduler. */
    public static Optional<Scope> current() { return Optional.ofNullable(CURRENT.get()); }

    /** Whether the calling thread is running a task submitted to {@code entity(e)} for an entity with {@code entity}'s UUID. */
    public static boolean inEntityTask(Entity entity) {
        return current().filter(scope -> scope.kind() == Scope.Kind.ENTITY)
                .map(scope -> entity.getUniqueId().equals(scope.entity())).orElse(false);
    }

    /** Whether the calling thread is running a task submitted to {@code global()}. */
    public static boolean inGlobalTask() {
        return current().map(scope -> scope.kind() == Scope.Kind.GLOBAL).orElse(false);
    }

    @Override public String owner() { return delegate.owner(); }
    @Override public TaskContext global() { return wrap(delegate.global(), Scope.Kind.GLOBAL, null); }
    @Override public TaskContext region(Location location) { return wrap(delegate.region(location), Scope.Kind.REGION, null); }
    @Override public TaskContext region(World world, int chunkX, int chunkZ) {
        return wrap(delegate.region(world, chunkX, chunkZ), Scope.Kind.REGION, null);
    }
    @Override public TaskContext entity(Entity entity) {
        return wrap(delegate.entity(entity), Scope.Kind.ENTITY, entity.getUniqueId());
    }
    @Override public TaskContext async() { return wrap(delegate.async(), Scope.Kind.ASYNC, null); }

    private static TaskContext wrap(TaskContext context, Scope.Kind kind, @Nullable UUID entity) {
        return new RecordedContext(context, new Scope(kind, entity));
    }

    private record RecordedContext(TaskContext delegate, Scope scope) implements TaskContext {
        private <T> T within(Supplier<? extends T> task) {
            Scope previous = CURRENT.get();
            CURRENT.set(scope);
            try {
                return task.get();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        }

        private Runnable recorded(Runnable task) {
            Objects.requireNonNull(task, "task");
            return () -> within(() -> {
                task.run();
                return null;
            });
        }

        @Override public TaskHandle run(Runnable task) { return delegate.run(recorded(task)); }
        @Override public TaskHandle runLater(Duration delay, Runnable task) { return delegate.runLater(delay, recorded(task)); }
        @Override public TaskHandle runRepeating(Duration initialDelay, Duration period, Consumer<? super TaskHandle> task) {
            Objects.requireNonNull(task, "task");
            return delegate.runRepeating(initialDelay, period, handle -> within(() -> {
                task.accept(handle);
                return null;
            }));
        }
        @Override public <T> CompletableFuture<T> supply(Supplier<? extends T> task) {
            Objects.requireNonNull(task, "task");
            return delegate.supply(() -> within(task));
        }
        @Override public boolean isCurrentThread() { return delegate.isCurrentThread(); }
        @Override public void execute(Runnable command) { delegate.execute(recorded(command)); }
    }
}

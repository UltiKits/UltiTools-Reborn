package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Validates submissions and maps an affinity to one backend delivery. */
@ApiStatus.Internal
final class Context implements TaskContext {
    enum Affinity { GLOBAL, REGION, ENTITY, ASYNC }

    final OwnedScheduler owner;
    private final Affinity affinity;
    private final @Nullable World world;
    private final int chunkX;
    private final int chunkZ;
    private final @Nullable Entity entity;

    Context(OwnedScheduler owner, Affinity affinity, @Nullable World world, int chunkX, int chunkZ,
            @Nullable Entity entity) {
        this.owner = owner;
        this.affinity = affinity;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.entity = entity;
    }

    @Override
    public TaskHandle run(Runnable task) {
        return runLater(Duration.ZERO, task);
    }

    @Override
    public TaskHandle runLater(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        long converted = convert(delay);
        return owner.submit(new TaskRecord(this, ignored -> task.run(), 0, null), converted);
    }

    @Override
    public TaskHandle runRepeating(Duration initialDelay, Duration period, Consumer<? super TaskHandle> task) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(task, "task");
        long delay = convert(initialDelay);
        if (period.isNegative() || period.isZero()
                || (affinity != Affinity.ASYNC && period.compareTo(Ticks.TICK) < 0)) {
            throw new IllegalArgumentException("period is too short for this context");
        }
        return owner.submit(new TaskRecord(this, task, convert(period), null), delay);
    }

    @Override
    public <T> CompletableFuture<T> supply(Supplier<? extends T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        TaskRecord record = new TaskRecord(this, ignored -> result.complete(task.get()), 0, result);
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                record.cancel();
            }
        });
        owner.submit(record, convert(Duration.ZERO));
        return result;
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (run(command).state() == TaskState.RETIRED) {
            throw new RejectedExecutionException("Entity removed before submission for " + owner.owner());
        }
    }

    @Override
    public boolean isCurrentThread() {
        PaperBackend backend = owner.engine.backend;
        switch (affinity) {
            case GLOBAL:
                return backend.isGlobalTickThread();
            case REGION:
                return backend.isOwnedByCurrentRegion(Objects.requireNonNull(world), chunkX, chunkZ);
            case ENTITY:
                return backend.isOwnedByCurrentRegion(Objects.requireNonNull(entity));
            default:
                return false;
        }
    }

    @Nullable ScheduledTask deliver(Consumer<ScheduledTask> task, Runnable retired, long delay) {
        PaperBackend backend = owner.engine.backend;
        switch (affinity) {
            case GLOBAL:
                return backend.global(task, delay);
            case REGION:
                return backend.region(Objects.requireNonNull(world), chunkX, chunkZ, task, delay);
            case ENTITY:
                return backend.entity(Objects.requireNonNull(entity), task, retired, delay);
            default:
                return backend.async(task, delay);
        }
    }

    private long convert(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return affinity == Affinity.ASYNC ? duration.toNanos() : Math.max(1, Ticks.ceil(duration));
    }
}

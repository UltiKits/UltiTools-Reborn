package com.ultikits.ultitools.scheduling.internal;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** Supplements MockBukkit with Paper's null-on-already-retired behaviour only. */
final class RetiringBackend implements PaperBackend {
    private final PaperBackend delegate;
    private final Set<UUID> retiredEntities = ConcurrentHashMap.newKeySet();

    RetiringBackend(PaperBackend delegate) {
        this.delegate = delegate;
    }

    void retire(Entity entity) {
        retiredEntities.add(entity.getUniqueId());
    }

    @Override
    public ScheduledTask global(Consumer<ScheduledTask> task, long ticks) {
        return delegate.global(task, ticks);
    }

    @Override
    public ScheduledTask region(World world, int x, int z, Consumer<ScheduledTask> task, long ticks) {
        return delegate.region(world, x, z, task, ticks);
    }

    @Override
    public @Nullable ScheduledTask entity(Entity entity, Consumer<ScheduledTask> task, Runnable retired, long ticks) {
        if (retiredEntities.contains(entity.getUniqueId())) {
            return null;
        }
        return delegate.entity(entity, handle -> {
            if (retiredEntities.contains(entity.getUniqueId())) {
                retired.run();
            } else {
                task.accept(handle);
            }
        }, retired, ticks);
    }

    @Override
    public ScheduledTask async(Consumer<ScheduledTask> task, long nanos) {
        return delegate.async(task, nanos);
    }

    @Override
    public boolean isGlobalTickThread() {
        return delegate.isGlobalTickThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(World world, int x, int z) {
        return delegate.isOwnedByCurrentRegion(world, x, z);
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        return delegate.isOwnedByCurrentRegion(entity);
    }
}

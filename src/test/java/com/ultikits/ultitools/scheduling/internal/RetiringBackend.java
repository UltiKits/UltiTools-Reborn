package com.ultikits.ultitools.scheduling.internal;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Supplements MockBukkit with Paper's synchronous retirement and null-on-retired behaviour. */
final class RetiringBackend implements PaperBackend {
    private final PaperBackend delegate;
    private final Set<UUID> retiredEntities = new HashSet<>();
    private final Map<UUID, List<EntityDelivery>> pending = new HashMap<>();

    RetiringBackend(PaperBackend delegate) {
        this.delegate = delegate;
    }

    synchronized void retire(Entity entity) {
        UUID id = entity.getUniqueId();
        retiredEntities.add(id);
        if (entity instanceof PlayerMock) {
            ((PlayerMock) entity).disconnect();
        } else {
            entity.remove();
        }
        List<EntityDelivery> queued = pending.remove(id);
        if (queued != null) {
            // Paper retires queued deliveries on the removal thread, not on their due tick.
            new ArrayList<>(queued).forEach(delivery -> delivery.retired.run());
        }
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
    public synchronized @Nullable ScheduledTask entity(Entity entity, Consumer<ScheduledTask> task,
                                                       Runnable retired, long ticks) {
        UUID id = entity.getUniqueId();
        if (retiredEntities.contains(id)) {
            return null;
        }
        EntityDelivery delivery = new EntityDelivery(id, task, retired);
        pending.computeIfAbsent(id, ignored -> new ArrayList<>()).add(delivery);
        return delegate.entity(entity, delivery::run, delivery::retire, ticks);
    }

    synchronized boolean take(EntityDelivery delivery) {
        List<EntityDelivery> queued = pending.get(delivery.id);
        if (queued == null || !queued.remove(delivery)) {
            return false;
        }
        if (queued.isEmpty()) {
            pending.remove(delivery.id);
        }
        return true;
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

    final class EntityDelivery {
        final UUID id;
        final Consumer<ScheduledTask> task;
        final Runnable retired;

        EntityDelivery(UUID id, Consumer<ScheduledTask> task, Runnable retired) {
            this.id = id;
            this.task = task;
            this.retired = retired;
        }

        void run(ScheduledTask handle) {
            if (take(this)) {
                task.accept(handle);
            }
        }

        void retire() {
            if (take(this)) {
                retired.run();
            }
        }
    }
}

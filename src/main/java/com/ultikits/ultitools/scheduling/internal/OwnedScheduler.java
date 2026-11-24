package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/** One independently retired owner; its name is diagnostic, never its identity. */
@ApiStatus.Internal
final class OwnedScheduler implements Scheduler {
    final PaperSchedulerEngine engine;
    final Set<TaskRecord> live = ConcurrentHashMap.newKeySet();
    private final String name;
    private final Context global;
    private final Context async;
    private volatile boolean retired;

    OwnedScheduler(PaperSchedulerEngine engine, String name) {
        this.engine = engine;
        this.name = name;
        global = new Context(this, Context.Affinity.GLOBAL, null, 0, 0, null);
        async = new Context(this, Context.Affinity.ASYNC, null, 0, 0, null);
    }

    @Override
    public String owner() {
        return name;
    }

    @Override
    public Context global() {
        return global;
    }

    @Override
    public Context async() {
        return async;
    }

    @Override
    public Context region(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location has no world");
        }
        return region(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    @Override
    public Context region(World world, int chunkX, int chunkZ) {
        return new Context(this, Context.Affinity.REGION, Objects.requireNonNull(world, "world"), chunkX, chunkZ, null);
    }

    @Override
    public Context entity(Entity entity) {
        return new Context(this, Context.Affinity.ENTITY, null, 0, 0, Objects.requireNonNull(entity, "entity"));
    }

    void checkActive() {
        if (retired) {
            throw new RejectedExecutionException("Owner unloaded: " + name);
        }
    }

    TaskHandle submit(TaskRecord record, long delay) {
        checkActive();
        live.add(record);
        // Paired with retire's flag-before-snapshot order: neither side can miss the other.
        if (retired) {
            record.cancel();
            checkActive();
        }
        try {
            record.schedule(delay);
        } catch (RuntimeException | Error failure) {
            record.submissionFailed(failure);
            throw failure;
        }
        return record;
    }

    void retire() {
        retired = true;
        for (TaskRecord record : new ArrayList<>(live)) {
            record.cancel();
        }
    }
}

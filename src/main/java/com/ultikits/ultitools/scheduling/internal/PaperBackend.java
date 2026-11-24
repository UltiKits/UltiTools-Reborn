package com.ultikits.ultitools.scheduling.internal;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The single-delivery seam between the lifecycle engine and Paper's schedulers. */
@ApiStatus.Internal
public interface PaperBackend {
    /** Schedules once on the global region after at least one tick. */
    ScheduledTask global(Consumer<ScheduledTask> task, long delayTicks);

    /** Schedules once on the region owning the chunk after at least one tick. */
    ScheduledTask region(World world, int chunkX, int chunkZ, Consumer<ScheduledTask> task, long delayTicks);

    /** Schedules once on an entity, or returns null if that entity has already retired. */
    @Nullable ScheduledTask entity(Entity entity, Consumer<ScheduledTask> task, Runnable retired, long delayTicks);

    /** Schedules once on the async pool, using nanoseconds rather than server ticks. */
    ScheduledTask async(Consumer<ScheduledTask> task, long delayNanos);

    /** Whether this thread owns global tick work. */
    boolean isGlobalTickThread();

    /** Whether this thread owns the chunk. */
    boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ);

    /** Whether this thread owns the entity. */
    boolean isOwnedByCurrentRegion(Entity entity);

    /** Creates a backend delegating to the live Paper APIs for the host plugin. */
    static PaperBackend live(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return new PaperBackend() {
            @Override
            public ScheduledTask global(Consumer<ScheduledTask> task, long delayTicks) {
                return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task, delayTicks);
            }

            @Override
            public ScheduledTask region(World world, int x, int z, Consumer<ScheduledTask> task, long delayTicks) {
                return Bukkit.getRegionScheduler().runDelayed(plugin, world, x, z, task, delayTicks);
            }

            @Override
            public @Nullable ScheduledTask entity(Entity entity, Consumer<ScheduledTask> task,
                                                   Runnable retired, long delayTicks) {
                return entity.getScheduler().runDelayed(plugin, task, retired, delayTicks);
            }

            @Override
            public ScheduledTask async(Consumer<ScheduledTask> task, long delayNanos) {
                return delayNanos == 0 ? Bukkit.getAsyncScheduler().runNow(plugin, task)
                        : Bukkit.getAsyncScheduler().runDelayed(plugin, task, delayNanos, TimeUnit.NANOSECONDS);
            }

            @Override
            public boolean isGlobalTickThread() {
                return Bukkit.isGlobalTickThread();
            }

            @Override
            public boolean isOwnedByCurrentRegion(World world, int x, int z) {
                return Bukkit.isOwnedByCurrentRegion(world, x, z);
            }

            @Override
            public boolean isOwnedByCurrentRegion(Entity entity) {
                return Bukkit.isOwnedByCurrentRegion(entity);
            }
        };
    }
}

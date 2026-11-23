package com.ultikits.ultitools.scheduling;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * An injected scheduler owned by one module. Unloading the owning module cancels every task of that
 * module that has not started and every repeating task; a one-shot task running at that moment
 * finishes and ends COMPLETED or FAILED. Every submission after the unload throws
 * RejectedExecutionException. Obtaining a context never throws because of unload; submitting to it does.
 *
 * <p>On Paper, global, region and entity contexts map to Paper's corresponding schedulers and
 * execute on the main thread; {@link #async()} maps to Paper's async scheduler. Callers must
 * still select the affinity matching the data they touch. This contract does not declare Folia support.
 */
@ApiStatus.NonExtendable
public interface Scheduler {
    /** Returns the owning module's name, or {@code UltiTools} for the framework, for diagnostics. */
    String owner();

    /** Returns the context for server-wide work, such as world time, weather or server state. */
    TaskContext global();

    /**
     * Returns the context owning the blocks/chunk at the location.
     * @throws IllegalArgumentException if the location has no world
     */
    TaskContext region(Location location);

    /** Returns the context owning the given chunk of the world. */
    TaskContext region(World world, int chunkX, int chunkZ);

    /**
     * Returns the context following an entity as it moves. Tasks of an entity that is removed from
     * the server are not run and end RETIRED: a non-player entity that dies, despawns or is discarded,
     * or a player who quits. A player who dies and respawns, and any entity changing dimension, keeps
     * its tasks.
     *
     * <p>On an entity that is already removed, run, runLater and runRepeating return a handle that is
     * already RETIRED (its completion fails with TaskRetiredException), supply returns a future already
     * failed with TaskRetiredException, and execute throws RejectedExecutionException.
     */
    TaskContext entity(Entity entity);

    /**
     * Returns the wall-clock context for I/O, networking or computation, never tick-thread work.
     * See {@link TaskContext} for delay, execution-thread and overlap guarantees.
     */
    TaskContext async();
}

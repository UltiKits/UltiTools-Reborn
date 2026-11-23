package com.ultikits.ultitools.scheduling;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * An injected scheduler owned by one module. Unloading that module cancels all its tasks;
 * every later submission throws {@link java.util.concurrent.RejectedExecutionException}.
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
     * Returns the context following an entity as it moves. An entity's pending tasks never run
     * after removal, death, unload or player disconnect; they end {@link TaskState#RETIRED}.
     */
    TaskContext entity(Entity entity);

    /** Returns the wall-clock context for I/O, networking or computation, never tick-thread work. */
    TaskContext async();
}

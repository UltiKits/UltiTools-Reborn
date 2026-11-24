package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.Scheduler;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/** Creates and retires independent task owners on Paper. */
@ApiStatus.Internal
public final class PaperSchedulerEngine {
    final Logger logger;
    final PaperBackend backend;

    /** Creates an engine for the host plugin, logger and single-delivery backend. */
    public PaperSchedulerEngine(Plugin plugin, Logger logger, PaperBackend backend) {
        Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** Creates a new owner, independent even from owners with the same diagnostic name. */
    public Scheduler newOwner(String ownerName) {
        return new OwnedScheduler(this, Objects.requireNonNull(ownerName, "ownerName"));
    }

    /** Retires an owner without interrupting already running one-shot work. */
    public void retire(Scheduler owner) {
        if (!(owner instanceof OwnedScheduler)) {
            throw new IllegalArgumentException("Owner was not created by this engine");
        }
        OwnedScheduler owned = (OwnedScheduler) owner;
        if (owned.engine != this) { // NOPMD - Owner identity belongs to the creating engine, not its name.
            throw new IllegalArgumentException("Owner was not created by this engine");
        }
        owned.retire();
    }
}

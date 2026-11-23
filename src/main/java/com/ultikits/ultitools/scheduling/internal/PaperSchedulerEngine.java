package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.Scheduler;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/** Creates and retires independent task owners on Paper. */
@ApiStatus.Internal
public final class PaperSchedulerEngine {
    /** Creates an engine for the host plugin. */
    public PaperSchedulerEngine(Plugin plugin, Logger logger, PaperBackend backend) {
        // Contract-first skeleton: no lifecycle implementation yet.
    }

    /** Creates a new owner, independent even from owners with the same diagnostic name. */
    public Scheduler newOwner(String ownerName) {
        throw new UnsupportedOperationException("Contract-first skeleton");
    }

    /** Retires an owner without interrupting already running one-shot work. */
    public void retire(Scheduler owner) {
        throw new UnsupportedOperationException("Contract-first skeleton");
    }
}

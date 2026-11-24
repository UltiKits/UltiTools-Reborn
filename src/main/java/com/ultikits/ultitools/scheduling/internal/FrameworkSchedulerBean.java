package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.scheduling.Scheduler;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Connects root-container destruction to the framework owner's lifetime. */
@ApiStatus.Internal
public final class FrameworkSchedulerBean {
    private final PaperSchedulerEngine engine;
    private final Scheduler scheduler;

    /** Retains the engine and its framework owner for root-container teardown. */
    public FrameworkSchedulerBean(PaperSchedulerEngine engine, Scheduler scheduler) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Cancels framework work and clears the bridge, leaving any replacement bridge untouched. */
    @PreDestroy
    public void close() {
        try {
            engine.retire(scheduler);
        } finally {
            FrameworkScheduling.uninstall(scheduler);
        }
    }
}

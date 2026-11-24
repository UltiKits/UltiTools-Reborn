package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.Scheduler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.ApiStatus;

/** Internal bridge for framework objects constructed outside the IoC container. */
@ApiStatus.Internal
public final class FrameworkScheduling {
    private static final AtomicReference<Scheduler> CURRENT = new AtomicReference<>();

    private FrameworkScheduling() {
        // Static-only bridge; module code receives a Scheduler through injection instead.
    }

    /** Returns the framework owner after its root container has installed it. */
    public static Scheduler scheduler() {
        Scheduler scheduler = CURRENT.get();
        if (scheduler == null) {
            throw new IllegalStateException("Framework scheduler is not installed");
        }
        return scheduler;
    }

    /** Installs the current root container's owner. */
    public static void install(Scheduler scheduler) {
        CURRENT.set(Objects.requireNonNull(scheduler, "scheduler"));
    }

    /** Removes this exact root owner's bridge without clearing a replacement. */
    public static void uninstall(Scheduler scheduler) {
        CURRENT.compareAndSet(Objects.requireNonNull(scheduler, "scheduler"), null);
    }
}

package com.ultikits.ultitools.abstracts.command;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.text.Messages;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Framework services for executors constructed outside the root container. */
@ApiStatus.Internal
public final class CommandRuntime {
    private static volatile Services testServices;

    private CommandRuntime() {
        // Static service lookup only; executors retain their public constructors.
    }

    /** Returns the framework's root-container messages, never a module's namespace binding. */
    public static Messages messages() {
        Services override = testServices;
        if (override != null) {
            return override.messages();
        }
        UltiTools plugin = UltiTools.getInstance();
        try {
            if (plugin != null && plugin.getDependenceManagers() != null) {
                Messages messages = plugin.getDependenceManagers().getContext().getBean(Messages.class);
                if (messages != null) {
                    return messages;
                }
            }
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("Framework messages are not available", unavailable);
        }
        throw new IllegalStateException("Framework messages are not available");
    }

    /** Returns the framework-owned scheduler, not the dispatching module's scheduler. */
    public static Scheduler scheduler() {
        Services override = testServices;
        return override == null ? FrameworkScheduling.scheduler() : override.scheduler();
    }

    /**
     * Temporarily replaces both services for a test; close scopes in reverse installation order.
     * @param messages test message service
     * @param scheduler test scheduler
     * @return a scope restoring the previous services
     */
    public static AutoCloseable override(Messages messages, Scheduler scheduler) {
        Services previous = testServices;
        testServices = new Services(Objects.requireNonNull(messages, "messages"),
                Objects.requireNonNull(scheduler, "scheduler"));
        return () -> testServices = previous;
    }

    private record Services(Messages messages, Scheduler scheduler) { }
}

package com.ultikits.ultitools.abstracts.command;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.text.Messages;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/** Framework services for executors constructed outside the root container. */
@ApiStatus.Internal
public final class CommandRuntime {
    private static volatile Services testServices;
    private static CommandWaitBudget shutdownBudget;
    private static Supplier<CommandWaitBudget> waitBudgets = CommandWaitBudget::new;

    /** Starts the command shutdown allowance once; repeated calls never replenish it. */
    public static synchronized void beginShutdown() {
        if (shutdownBudget == null) {
            shutdownBudget = waitBudgets.get();
        }
    }

    /** Starts a new framework enable lifecycle, with no active server-stop allowance. */
    public static synchronized void beginEnable() {
        shutdownBudget = null;
    }

    static synchronized CommandWaitBudget waitBudget() {
        return shutdownBudget == null ? waitBudgets.get() : shutdownBudget;
    }

    static AutoCloseable overrideWaitBudgets(Supplier<CommandWaitBudget> factory) {
        Supplier<CommandWaitBudget> previous = waitBudgets;
        CommandWaitBudget previousShutdown = shutdownBudget;
        waitBudgets = factory;
        shutdownBudget = null;
        return () -> {
            waitBudgets = previous;
            shutdownBudget = previousShutdown;
        };
    }

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
     * Drains instantiated command executors before their container's other resources are released.
     * Aliases of the same bean are visited only once; no lazy beans are constructed.
     * All executors stop accepting work before the first wait. A module unload shares one
     * ten-second allowance; during server stop every drain shares the shutdown's remaining
     * allowance instead. Only actual command waiting consumes it, not intervening service cleanup.
     * @param beans the container's existing singleton values
     */
    public static void awaitAsyncCommands(Iterable<?> beans) {
        CommandWaitBudget budget = waitBudget();
        Set<BaseCommandExecutor> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<BaseCommandExecutor> executors = new ArrayList<>();
        for (Object bean : beans) {
            if (bean instanceof BaseCommandExecutor executor && seen.add(executor)) {
                executor.beginAsyncUnload(budget);
                executors.add(executor);
            }
        }
        for (BaseCommandExecutor executor : executors) {
            executor.awaitAsyncCommands();
        }
    }

    /**
     * Temporarily replaces both services for a test; close scopes in reverse installation order.
     * @param messages test message service
     * @param scheduler test scheduler
     * @return a scope restoring the previous services
     */
    static AutoCloseable override(Messages messages, Scheduler scheduler) {
        Services previous = testServices;
        testServices = new Services(Objects.requireNonNull(messages, "messages"),
                Objects.requireNonNull(scheduler, "scheduler"));
        return () -> testServices = previous;
    }

    private record Services(Messages messages, Scheduler scheduler) { }
}

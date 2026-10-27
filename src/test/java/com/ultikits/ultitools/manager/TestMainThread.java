package com.ultikits.ultitools.manager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/**
 * Stands in for the Bukkit main-thread scheduler in tests. Tasks handed to {@link #execute} wait
 * in a queue until the test drains them, so a production class that hops to the main thread is
 * observably deferred: nothing it does may happen on the dispatching thread.
 *
 * <p>{@link #runOffTheMainThread} drives the production entry points from a thread that is not
 * the MockBukkit server's primary thread, which is how a socket thread looks to the framework.</p>
 */
final class TestMainThread implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
        synchronized (tasks) {
            tasks.addLast(command);
        }
    }

    /** @return how many tasks are still waiting for the main thread */
    int pending() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    /** Runs everything queued so far, in submission order, on the calling thread. */
    void drain() {
        while (true) {
            Runnable next;
            synchronized (tasks) {
                next = tasks.pollFirst();
            }
            if (next == null) {
                return;
            }
            next.run();
        }
    }

    /** Runs {@code action} on a thread of its own and rethrows whatever it threw. */
    static void runOffTheMainThread(Runnable action) throws InterruptedException {
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure[0] = error;
            }
        }, "socket-thread");
        thread.start();
        thread.join(10000L);
        if (thread.isAlive()) {
            throw new AssertionError("The off-main-thread action did not finish");
        }
        if (failure[0] != null) {
            throw new AssertionError("The off-main-thread action failed", failure[0]);
        }
    }
}

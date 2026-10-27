package com.ultikits.ultitools.utils;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * One dedicated worker thread for the file and {@code server.properties} requests of one connection (plan 22-21 fix
 * round). Those handlers do blocking disk I/O -- a {@code files.list} of a directory with tens of thousands of
 * entries takes seconds -- so they must not run on the connection's own thread, where every heartbeat, ack and other
 * request would wait behind them.
 * <p>
 * <b>Order.</b> One thread and a FIFO queue: the wrapped requests are handled one at a time, in arrival order, so a
 * {@code files.write} followed by a {@code files.read} of the same path still reads what was written.
 * <p>
 * <b>Bounds.</b> At most {@code capacity} requests wait; one more is answered at once with the generic
 * {@code error} {@code rate_limited} (contract section 6) and never runs. The thread is a daemon named
 * {@value #THREAD_NAME} and ends when idle.
 * <p>
 * <b>Failures.</b> The router no longer surrounds a handler that runs here, so this worker answers as the router
 * would: an {@link InvalidFrameException} becomes {@code invalid_message} (its code), any other runtime failure
 * {@code internal_error} without the exception text. The reply goes out through the connection's channel.
 * <p>
 * <b>Close.</b> {@link #close()} drops the requests that had not started (their connection is gone, so a reply could
 * not reach the gateway, and an unanswered request is one the gateway reports as timed out -- never run twice) and
 * lets the running one finish. Never waits for the thread.
 * <p>
 * <b>Shutdown.</b> A request the {@link ShutdownGate} covers ({@code files.write}, {@code files.delete},
 * {@code properties.update}) asks the gate again when it starts here, because it may have been queued before
 * {@code UltiTools#onDisable} began: refused, it is answered with its shutting-down failure and never runs; let in, it
 * keeps the gate's permit until it is answered, so {@code onDisable} waits for it before the action log closes.
 */
final class SerialRequestWorker {

    /** The name of the worker thread. */
    static final String THREAD_NAME = "UltiTools-CloudFiles";
    /** How many file requests may wait behind the running one. */
    static final int DEFAULT_CAPACITY = 64;

    private static final Logger LOGGER = Logger.getLogger(SerialRequestWorker.class.getName());
    private static final long IDLE_SECONDS = 30L;

    private final BlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor executor;
    private final ShutdownGate shutdown;

    /**
     * A worker whose requests are never refused for a shutdown.
     *
     * @param capacity how many requests may wait behind the running one; at least 1
     */
    SerialRequestWorker(int capacity) {
        this(capacity, new ShutdownGate(ShutdownGate.Signal.OPEN, null));
    }

    /**
     * @param capacity how many requests may wait behind the running one; at least 1
     * @param shutdown the shutdown gate a covered request asks when it starts
     */
    SerialRequestWorker(int capacity, ShutdownGate shutdown) {
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.executor = new ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, queue, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @param handler the blocking handler
     * @return a handler that queues each request for this worker and returns at once
     */
    CloudRequestHandler wrap(final CloudRequestHandler handler) {
        return new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel channel) {
                submit(handler, request, channel);
            }
        };
    }

    /** Drops the requests that had not started and stops the thread once the running one ends. */
    void close() {
        executor.shutdown();
        queue.clear();
    }

    private void submit(final CloudRequestHandler handler, final CloudRequest request, final CloudChannel channel) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    handleUnlessShuttingDown(handler, request, channel);
                }
            });
        } catch (RejectedExecutionException refused) {
            if (executor.isShutdown()) {
                // The connection closed; nothing can answer on it any more.
                return;
            }
            LOGGER.warning("UltiCloud: too many file requests are waiting; " + request.type() + " was refused.");
            channel.replyError(request.id(), "error", "rate_limited",
                    "Too many file requests are waiting on this server; try again shortly.", null);
        }
    }

    private void handleUnlessShuttingDown(CloudRequestHandler handler, CloudRequest request, CloudChannel channel) {
        if (!ShutdownGate.covers(request.type())) {
            handle(handler, request, channel);
            return;
        }
        if (!shutdown.enter()) {
            shutdown.refuse(request, channel);
            return;
        }
        try {
            handle(handler, request, channel);
        } finally {
            shutdown.exit();
        }
    }

    // A handler failure must still answer the request, exactly as AgentMessageRouter#dispatch does.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void handle(CloudRequestHandler handler, CloudRequest request, CloudChannel channel) {
        try {
            handler.handle(request, channel);
        } catch (InvalidFrameException invalid) {
            channel.replyError(request.id(), "error", invalid.code(), "Payload does not match the message schema.",
                    null);
        } catch (RuntimeException failure) {
            LOGGER.warning("UltiCloud: agent request rejected (internal_error); type=" + request.type()
                    + ", exception=" + failure.getClass().getName() + ".");
            channel.replyError(request.id(), "error", "internal_error", "The request could not be processed.", null);
        }
    }
}

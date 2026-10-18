package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;

/**
 * Every way an admitted panel responder request can end (UltiTools-Reborn#677): each settles the
 * outcome exactly once and promptly, {@code dispatch} never throws, and shutdown settles what it
 * admitted without interrupting a running module call. Uses the package-private constructor with
 * short deadlines and one worker so queueing is deterministic.
 * <br>
 * 面板 responder 请求的每一种结束方式：恰好结算一次、及时结算，dispatch 从不抛出，关闭不打断运行中的调用。
 */
@DisplayName("#677 panel responder request lifecycle")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PanelResponderRegistryLifecycleTest {

    private static final Duration PROMPT = Duration.ofSeconds(1);
    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final long LONG_DEADLINE_MILLIS = 5000L;

    private final List<PanelResponderRegistry> registries = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (CountDownLatch latch : latches) {
            latch.countDown();
        }
        for (PanelResponderRegistry registry : registries) {
            registry.shutdown();
        }
    }

    private PanelResponderRegistry registry(long deadlineMillis, int workers, int capacity) {
        PanelResponderRegistry registry = new PanelResponderRegistry(deadlineMillis, workers, capacity);
        registries.add(registry);
        return registry;
    }

    private CountDownLatch latch() {
        CountDownLatch latch = new CountDownLatch(1);
        latches.add(latch);
        return latch;
    }

    private static JsonObject result(String from) {
        JsonObject result = new JsonObject();
        result.addProperty("from", from);
        return result;
    }

    /** A responder that signals {@code started}, blocks on {@code release} and signals {@code returned}. */
    private static Function<JsonObject, CompletableFuture<JsonObject>> blocking(CountDownLatch started,
            CountDownLatch release, CountDownLatch returned, AtomicBoolean interrupted, AtomicReference<Thread> thread) {
        return data -> {
            thread.set(Thread.currentThread());
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            if (Thread.currentThread().isInterrupted()) {
                interrupted.set(true);
            }
            returned.countDown();
            return CompletableFuture.completedFuture(result("blocking"));
        };
    }

    private static Function<JsonObject, CompletableFuture<JsonObject>> counting(AtomicInteger calls) {
        return data -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(result("counting"));
        };
    }

    /**
     * A deadline timer whose first {@code shutdownNow()} signals {@code entered} and then waits for
     * {@code proceed}. {@link PanelResponderRegistry#shutdown()} stops the timer right after it sets
     * its shutdown flag and before it empties the worker queue, so this holds a shutdown inside the
     * window in which it has begun but not yet settled what it admitted.
     */
    private static final class GatedTimer extends ScheduledThreadPoolExecutor {
        private final CountDownLatch entered;
        private final CountDownLatch proceed;
        private final AtomicBoolean gateOpen = new AtomicBoolean(true);

        private GatedTimer(CountDownLatch entered, CountDownLatch proceed) {
            super(1, r -> {
                Thread thread = new Thread(r, "test-gated-deadline-timer");
                thread.setDaemon(true);
                return thread;
            });
            setRemoveOnCancelPolicy(true);
            this.entered = entered;
            this.proceed = proceed;
        }

        @Override
        public List<Runnable> shutdownNow() {
            if (gateOpen.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    proceed.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.shutdownNow();
        }
    }

    /** Replaces the registry's deadline timer before any request is admitted, stopping the original. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reaches the private timer to hold shutdown() at a known point
    private static void replaceDeadlineTimer(PanelResponderRegistry registry, ScheduledThreadPoolExecutor timer)
            throws ReflectiveOperationException {
        Field field = PanelResponderRegistry.class.getDeclaredField("timeoutScheduler");
        field.setAccessible(true);
        ScheduledThreadPoolExecutor original = (ScheduledThreadPoolExecutor) field.get(registry);
        field.set(registry, timer);
        original.shutdownNow();
    }

    /** Throws {@code throwable} through an unchecked type parameter, as module code compiled elsewhere can. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    // ---- RED after Task 1 ----

    @Test
    @DisplayName("a sneakily thrown checked exception settles the outcome promptly with that exception")
    void sneakyCheckedExceptionSettlesPromptly() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        Exception checked = new Exception("checked failure thrown by a test responder");
        registry.registerResponder("sneaky", data -> {
            throw PanelResponderRegistryLifecycleTest.<RuntimeException>sneakyThrow(checked);
        }, "module-a");

        CompletableFuture<JsonObject> outcome = registry.dispatch("sneaky", new JsonObject(), "req-1");

        assertThat(outcome).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isSameAs(checked);
    }

    @Test
    @DisplayName("a linkage error from a closed module class loader settles the outcome promptly with that error")
    void linkageErrorSettlesPromptly() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        NoClassDefFoundError linkage = new NoClassDefFoundError("com/example/module/Gone");
        registry.registerResponder("linkage", data -> {
            throw linkage;
        }, "module-a");

        CompletableFuture<JsonObject> outcome = registry.dispatch("linkage", new JsonObject(), "req-1");

        assertThat(outcome).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isSameAs(linkage);
    }

    @Test
    @DisplayName("a request beyond the bounded queue is refused at once, naming type and owner, without executor internals")
    void fullQueueRefusesAtOnceNamingTypeAndOwner() throws Exception {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 1);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        registry.registerResponder("busy", blocking(started, release, latch(), new AtomicBoolean(),
                new AtomicReference<>()), "module-a");
        AtomicInteger calls = new AtomicInteger();
        registry.registerResponder("crowded", counting(calls), "module-b");

        registry.dispatch("busy", new JsonObject(), "req-1");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the first call occupies the only worker").isTrue();
        registry.dispatch("crowded", new JsonObject(), "req-2");
        assertThat(registry.queuedInvocationCountForTesting()).as("the second request fills the queue").isEqualTo(1);

        AtomicReference<CompletableFuture<JsonObject>> refused = new AtomicReference<>();
        assertThatCode(() -> refused.set(registry.dispatch("crowded", new JsonObject(), "req-3")))
                .doesNotThrowAnyException();

        assertThat(refused.get()).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(RejectedExecutionException.class, e -> {
                    assertThat(e.getMessage())
                            .contains("crowded")
                            .contains("module-b")
                            .doesNotContain("ThreadPoolExecutor")
                            .doesNotContain("@");
                    assertThat(e.getCause()).as("the executor's own exception is not chained").isNull();
                });
        assertThat(registry.pendingTimeoutTaskCountForTesting())
                .as("only the two admitted requests hold a deadline")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a caller cancelling a queued request frees its slot and deadline at once, and it never runs")
    void callerCancellationFreesTheSlotAndNeverRuns() throws Exception {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        CountDownLatch returned = latch();
        registry.registerResponder("busy", blocking(started, release, returned, new AtomicBoolean(),
                new AtomicReference<>()), "module-a");
        AtomicInteger cancelledCalls = new AtomicInteger();
        registry.registerResponder("cancelled", counting(cancelledCalls), "module-b");
        AtomicInteger laterCalls = new AtomicInteger();
        registry.registerResponder("later", counting(laterCalls), "module-c");

        CompletableFuture<JsonObject> busy = registry.dispatch("busy", new JsonObject(), "req-1");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the first call occupies the only worker").isTrue();
        busy.cancel(false);
        CompletableFuture<JsonObject> queued = registry.dispatch("cancelled", new JsonObject(), "req-2");
        assertThat(registry.queuedInvocationCountForTesting()).as("the second request is queued").isEqualTo(1);

        queued.cancel(false);
        int queuedAfterCancel = registry.queuedInvocationCountForTesting();
        int deadlinesAfterCancel = registry.pendingTimeoutTaskCountForTesting();

        assertThat(queued).failsWithin(PROMPT).withThrowableOfType(CancellationException.class);
        assertThat(queuedAfterCancel).as("the cancelled request left the worker queue").isZero();
        assertThat(deadlinesAfterCancel).as("no deadline remains for cancelled requests").isZero();

        release.countDown();
        assertThat(returned.await(5, TimeUnit.SECONDS)).as("the first call returned").isTrue();
        assertThat(registry.dispatch("later", new JsonObject(), "req-3"))
                .as("the worker is free again and serves the next request")
                .succeedsWithin(BOUND);
        assertThat(cancelledCalls).as("the cancelled request's responder was never called").hasValue(0);
    }

    @Test
    @DisplayName("shutdown settles every admitted request, runs no queued responder and interrupts no running call")
    void shutdownSettlesEveryAdmittedRequestWithoutInterrupting() throws Exception {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        CountDownLatch returned = latch();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        registry.registerResponder("running", blocking(started, release, returned, interrupted, worker), "module-a");
        AtomicInteger queuedCalls = new AtomicInteger();
        registry.registerResponder("waiting", counting(queuedCalls), "module-b");

        CompletableFuture<JsonObject> running = registry.dispatch("running", new JsonObject(), "req-1");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the first call occupies the only worker").isTrue();
        CompletableFuture<JsonObject> waiting = registry.dispatch("waiting", new JsonObject(), "req-2");

        registry.shutdown();

        assertThat(running).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(RejectedExecutionException.class, e -> assertThat(e.getMessage())
                        .contains("shut down").contains("running").contains("module-a"));
        assertThat(waiting).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(RejectedExecutionException.class, e -> assertThat(e.getMessage())
                        .contains("shut down").contains("waiting").contains("module-b"));

        release.countDown();
        assertThat(returned.await(5, TimeUnit.SECONDS)).as("the running call returned on its own").isTrue();
        worker.get().join(5000L);
        assertThat(worker.get().isAlive()).as("the worker finished all its work and ended").isFalse();
        assertThat(interrupted).as("the running call was not interrupted").isFalse();
        assertThat(queuedCalls).as("the queued responder never ran").hasValue(0);
        assertThat(running).as("the running call's late result is ignored").isCompletedExceptionally();
    }

    @Test
    @DisplayName("dispatch after shutdown returns an already failed outcome and does not throw")
    void dispatchAfterShutdownFailsWithoutThrowing() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        AtomicInteger calls = new AtomicInteger();
        registry.registerResponder("late", counting(calls), "module-a");
        registry.shutdown();

        AtomicReference<CompletableFuture<JsonObject>> outcome = new AtomicReference<>();
        assertThatCode(() -> outcome.set(registry.dispatch("late", new JsonObject(), "req-1")))
                .doesNotThrowAnyException();

        assertThat(outcome.get()).as("already failed when dispatch returns").isCompletedExceptionally();
        assertThat(outcome.get()).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(RejectedExecutionException.class, e -> assertThat(e.getMessage())
                        .contains("shut down").contains("late").contains("module-a").doesNotContain("@"));
        assertThat(calls).hasValue(0);
    }

    // ---- RED for the PR #682 review round ----

    @Test
    @DisplayName("a queued request a worker takes after shutdown began fails with the shutdown failure and its responder never runs")
    void queuedRequestTakenAfterShutdownBeganNeverRuns() throws Exception {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        CountDownLatch shutdownBegan = latch();
        CountDownLatch letShutdownContinue = latch();
        replaceDeadlineTimer(registry, new GatedTimer(shutdownBegan, letShutdownContinue));
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        CountDownLatch returned = latch();
        registry.registerResponder("running", blocking(started, release, returned, new AtomicBoolean(),
                new AtomicReference<>()), "module-a");
        AtomicInteger queuedCalls = new AtomicInteger();
        CountDownLatch queuedReached = latch();
        registry.registerResponder("waiting", data -> {
            queuedCalls.incrementAndGet();
            queuedReached.countDown();
            return CompletableFuture.completedFuture(result("waiting"));
        }, "module-b");

        registry.dispatch("running", new JsonObject(), "req-1");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the first call occupies the only worker").isTrue();
        CompletableFuture<JsonObject> waiting = registry.dispatch("waiting", new JsonObject(), "req-2");
        waiting.whenComplete((value, failure) -> queuedReached.countDown());
        assertThat(registry.queuedInvocationCountForTesting()).as("the second request is queued").isEqualTo(1);

        Thread stopping = new Thread(registry::shutdown, "test-registry-shutdown");
        stopping.setDaemon(true);
        stopping.start();
        assertThat(shutdownBegan.await(5, TimeUnit.SECONDS))
                .as("shutdown() has begun and is held before it empties the queue").isTrue();
        // The running call returns, so the only worker takes the request still queued.
        release.countDown();
        assertThat(queuedReached.await(5, TimeUnit.SECONDS))
                .as("the worker took the queued request: its responder ran or its outcome settled").isTrue();
        int callsWhileShuttingDown = queuedCalls.get();
        letShutdownContinue.countDown();
        stopping.join(5000L);

        assertThat(callsWhileShuttingDown)
                .as("a request not yet started when shutdown began never reaches its responder").isZero();
        assertThat(waiting).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(RejectedExecutionException.class, e -> assertThat(e.getMessage())
                        .contains("shut down").contains("waiting").contains("module-b"));
        assertThat(queuedCalls).hasValue(0);
    }

    @Test
    @DisplayName("the responder works on its own copy of the request data, so its changes never reach the caller's object")
    void responderChangesItsOwnCopyOfTheRequestData() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        AtomicReference<JsonObject> seen = new AtomicReference<>();
        registry.registerResponder("mutates", data -> {
            seen.set(data.deepCopy());
            data.remove("k");
            data.addProperty("added", "by-responder");
            return CompletableFuture.completedFuture(data);
        }, "module-a");
        JsonObject request = new JsonObject();
        request.addProperty("k", "v");
        request.addProperty("requestId", "req-1");
        JsonObject asReceived = request.deepCopy();

        CompletableFuture<JsonObject> outcome = registry.dispatch("mutates", request, "req-1");

        assertThat(outcome).succeedsWithin(BOUND).isNotSameAs(request);
        assertThat(seen.get()).as("the responder saw the request as received").isEqualTo(asReceived);
        assertThat(request).as("the caller's object is not changed by the responder").isEqualTo(asReceived);
    }

    // ---- Characterization controls (already pass after Task 1) ----

    @Test
    @DisplayName("control: an unchecked exception from the call becomes the outcome")
    // PMD.AvoidThrowingRawExceptionTypes: a module responder throwing a raw RuntimeException is the case under test.
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    void runtimeExceptionBecomesTheOutcome() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        RuntimeException boom = new RuntimeException("boom - deliberately thrown by a test responder");
        registry.registerResponder("throws", data -> {
            throw boom;
        }, "module-a");

        assertThat(registry.dispatch("throws", new JsonObject(), "req-1")).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isSameAs(boom);
    }

    @Test
    @DisplayName("control: a null return fails with a message naming type and owner")
    void nullReturnFails() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        registry.registerResponder("returns-null", data -> null, "module-a");

        assertThat(registry.dispatch("returns-null", new JsonObject(), "req-1")).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOf(IllegalStateException.class)
                .withMessageContaining("returns-null")
                .withMessageContaining("module-a")
                .withMessageContaining("returned null");
    }

    @Test
    @DisplayName("control: a null completion fails with a message naming type and owner")
    void nullCompletionFails() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        registry.registerResponder("completes-null", data -> CompletableFuture.completedFuture(null), "module-a");

        assertThat(registry.dispatch("completes-null", new JsonObject(), "req-1")).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOf(IllegalStateException.class)
                .withMessageContaining("completes-null")
                .withMessageContaining("module-a")
                .withMessageContaining("completed with null");
    }

    @Test
    @DisplayName("control: an exceptional completion is relayed as the outcome")
    void exceptionalCompletionIsRelayed() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);
        IllegalArgumentException failure = new IllegalArgumentException("refused by a test responder");
        CompletableFuture<JsonObject> failed = new CompletableFuture<>();
        failed.completeExceptionally(failure);
        registry.registerResponder("fails", data -> failed, "module-a");

        assertThat(registry.dispatch("fails", new JsonObject(), "req-1")).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isSameAs(failure);
    }

    @Test
    @DisplayName("control: a completion after the deadline is ignored")
    void lateCompletionAfterDeadlineIsIgnored() {
        PanelResponderRegistry registry = registry(200L, 1, 4);
        CompletableFuture<JsonObject> responderFuture = new CompletableFuture<>();
        registry.registerResponder("slow", data -> responderFuture, "module-a");

        CompletableFuture<JsonObject> outcome = registry.dispatch("slow", new JsonObject(), "req-1");
        assertThat(outcome).failsWithin(BOUND)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(PluginModuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WEBSOCKET_RESPONDER_TIMEOUT));

        assertThat(responderFuture.complete(result("late"))).as("the late result was delivered").isTrue();

        assertThat(outcome).failsWithin(PROMPT)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOf(PluginModuleException.class);
    }

    @Test
    @DisplayName("control: a deadline while queued frees the slot and the queued responder never runs")
    void deadlineWhileQueuedFreesTheSlotAndNeverRuns() throws Exception {
        PanelResponderRegistry registry = registry(500L, 1, 4);
        CountDownLatch started = latch();
        CountDownLatch release = latch();
        CountDownLatch returned = latch();
        registry.registerResponder("busy", blocking(started, release, returned, new AtomicBoolean(),
                new AtomicReference<>()), "module-a");
        AtomicInteger queuedCalls = new AtomicInteger();
        registry.registerResponder("queued", counting(queuedCalls), "module-b");
        AtomicInteger laterCalls = new AtomicInteger();
        registry.registerResponder("later", counting(laterCalls), "module-c");

        registry.dispatch("busy", new JsonObject(), "req-1");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("the first call occupies the only worker").isTrue();
        CompletableFuture<JsonObject> queued = registry.dispatch("queued", new JsonObject(), "req-2");

        assertThat(queued).failsWithin(BOUND)
                .withThrowableOfType(ExecutionException.class)
                .havingCause()
                .isInstanceOfSatisfying(PluginModuleException.class, e -> assertThat(e.getMessage())
                        .contains("queued").contains("module-b"));
        assertThat(registry.queuedInvocationCountForTesting()).as("the expired request left the queue").isZero();

        release.countDown();
        assertThat(returned.await(5, TimeUnit.SECONDS)).as("the first call returned").isTrue();
        assertThat(registry.dispatch("later", new JsonObject(), "req-3"))
                .as("the worker is free again and serves the next request")
                .succeedsWithin(BOUND);
        assertThat(queuedCalls).as("the expired request's responder never ran").hasValue(0);
        assertThat(laterCalls).hasValue(1);
    }

    @Test
    @DisplayName("control: shutdown stops both the deadline timer and the workers")
    void shutdownStopsTimerAndWorkers() {
        PanelResponderRegistry registry = registry(LONG_DEADLINE_MILLIS, 1, 4);

        registry.shutdown();

        assertThat(registry.isTimeoutSchedulerShutdownForTesting()).isTrue();
        assertThat(registry.isWorkerPoolShutdownForTesting()).isTrue();
    }
}

package com.ultikits.ultitools.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 22-23 second verification: {@link PanelResponderRegistry#dispatch(String, JsonObject, String, Runnable)} tells its
 * caller when the module's work for a request has ended, once, which is what the shutdown wait holds its permit for.
 */
@DisplayName("PanelResponderRegistry: the work-ended hook runs once, when the module's work ends")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PanelResponderWorkEndedTest {

    private static final String TYPE = "ultimail.inbox.list";
    private static final String OWNER = "UltiMail";

    private PanelResponderRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdown();
        }
    }

    @Test
    @DisplayName("a responder still running at the deadline: the future settles, the hook waits for the responder")
    void hookWaitsForTheResponderNotTheDeadline() throws Exception {
        registry = new PanelResponderRegistry(100L, 1, 4);
        final CountDownLatch release = new CountDownLatch(1);
        registry.registerResponder(TYPE, data -> {
            await(release);
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);
        AtomicInteger ended = new AtomicInteger();

        CompletableFuture<JsonElement> outcome = registry.dispatch(TYPE, new JsonObject(), "id", ended::incrementAndGet);

        assertThat(outcome).failsWithin(5, TimeUnit.SECONDS);
        assertThat(ended).as("the responder is still running").hasValue(0);
        release.countDown();
        waitFor(() -> ended.get() == 1);
        Thread.sleep(100L);
        assertThat(ended).as("exactly once").hasValue(1);
    }

    @Test
    @DisplayName("a request taken out of the queue before it ran: the hook runs once and the responder never")
    void hookRunsOnceForARequestThatNeverStarted() throws Exception {
        registry = new PanelResponderRegistry(10_000L, 1, 4);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        registry.registerResponder(TYPE, data -> {
            calls.incrementAndGet();
            await(release);
            return CompletableFuture.completedFuture(new JsonObject());
        }, OWNER);
        AtomicInteger firstEnded = new AtomicInteger();
        AtomicInteger secondEnded = new AtomicInteger();
        registry.dispatch(TYPE, new JsonObject(), "first", firstEnded::incrementAndGet);
        waitFor(() -> calls.get() == 1);

        CompletableFuture<JsonElement> second = registry.dispatch(TYPE, new JsonObject(), "second",
                secondEnded::incrementAndGet);
        second.cancel(false);

        assertThat(secondEnded).hasValue(1);
        release.countDown();
        waitFor(() -> firstEnded.get() == 1);
        Thread.sleep(100L);
        assertThat(calls).as("the cancelled request never ran").hasValue(1);
        assertThat(secondEnded).as("exactly once").hasValue(1);
    }

    @Test
    @DisplayName("no responder, and a responder that throws: the hook runs at once")
    void hookRunsWhenNothingIsLeftToRun() throws Exception {
        registry = new PanelResponderRegistry(10_000L, 1, 4);
        AtomicInteger ended = new AtomicInteger();
        registry.dispatch("nobody.here", new JsonObject(), "id", ended::incrementAndGet);
        assertThat(ended).hasValue(1);

        registry.registerResponder(TYPE, data -> {
            throw new IllegalStateException("module failure");
        }, OWNER);
        registry.dispatch(TYPE, new JsonObject(), "id2", ended::incrementAndGet);
        waitFor(() -> ended.get() == 2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("timed out").isLessThan(end);
            Thread.sleep(10L);
        }
    }
}

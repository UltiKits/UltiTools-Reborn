package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * Plan 22-21 fix round, gap 2: file and {@code server.properties} requests run on one dedicated worker thread, not on
 * the connection's thread. One thread keeps their arrival order; a full queue answers {@code rate_limited}; a payload
 * the handler rejects still gets the router's {@code invalid_message} and an unexpected failure its
 * {@code internal_error}; closing the worker drops the requests that had not started.
 */
@DisplayName("SerialRequestWorker: file requests off the connection thread, in order, bounded")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SerialRequestWorkerTest {

    private static final String ID_A = "01JB2Z8Y3K4M5N6P7Q8R9S0AAA";
    private static final String ID_B = "01JB2Z8Y3K4M5N6P7Q8R9S0BBB";
    private static final String ID_C = "01JB2Z8Y3K4M5N6P7Q8R9S0CCC";

    private final RecordingChannel channel = new RecordingChannel();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private SerialRequestWorker worker;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (worker != null) {
            worker.close();
        }
    }

    /** Records the request on the worker, waits for the latch on the first one, then answers. */
    private final CloudRequestHandler blocking = new CloudRequestHandler() {
        @Override
        public void handle(CloudRequest request, CloudChannel reply) {
            threads.add(Thread.currentThread().getName() + "/" + Thread.currentThread().isDaemon());
            if (handled.isEmpty()) {
                await(release);
            }
            handled.add(request.id());
            // A schema-valid outcome: the recording channel checks every frame against the contract.
            reply.replyError(request.id(), "files.list.result", "internal_error", "Listed.", null);
        }
    };

    private static CloudRequest request(String id) {
        return new CloudRequest(id, "files.list", 1000L, null, new JsonObject());
    }

    @Test
    @DisplayName("the handler runs on the named daemon worker and the caller returns at once")
    void runsOnTheWorkerAndReturnsImmediately() throws Exception {
        worker = new SerialRequestWorker(4);
        CloudRequestHandler wrapped = worker.wrap(blocking);
        long started = System.nanoTime();
        wrapped.handle(request(ID_A), channel);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("the caller is not blocked")
                .isLessThan(1000L);
        waitFor(() -> !threads.isEmpty());
        assertThat(threads.get(0)).isEqualTo(SerialRequestWorker.THREAD_NAME + "/true");
        assertThat(channel.frames()).as("no reply before the handler finishes").isEmpty();
        release.countDown();
        waitFor(() -> channel.frames().size() == 1);
        assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(ID_A);
    }

    @Test
    @DisplayName("requests are handled one at a time in arrival order")
    void keepsArrivalOrder() throws Exception {
        worker = new SerialRequestWorker(4);
        CloudRequestHandler wrapped = worker.wrap(blocking);
        wrapped.handle(request(ID_A), channel);
        wrapped.handle(request(ID_B), channel);
        wrapped.handle(request(ID_C), channel);
        release.countDown();
        waitFor(() -> handled.size() == 3);
        assertThat(handled).containsExactly(ID_A, ID_B, ID_C);
    }

    @Test
    @DisplayName("a full queue answers rate_limited at once and never runs the request")
    void fullQueueAnswersRateLimited() throws Exception {
        worker = new SerialRequestWorker(1);
        CloudRequestHandler wrapped = worker.wrap(blocking);
        wrapped.handle(request(ID_A), channel);
        waitFor(() -> !threads.isEmpty()); // A runs (blocked), the queue is empty
        wrapped.handle(request(ID_B), channel); // queued
        wrapped.handle(request(ID_C), channel); // overflow
        waitFor(() -> channel.frames().size() == 1);
        JsonObject refusal = channel.frames().get(0);
        assertThat(refusal.get("type").getAsString()).isEqualTo("error");
        assertThat(refusal.get("replyTo").getAsString()).isEqualTo(ID_C);
        assertThat(refusal.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("rate_limited");
        release.countDown();
        waitFor(() -> handled.size() == 2);
        assertThat(handled).containsExactly(ID_A, ID_B);
    }

    @Test
    @DisplayName("a payload the handler rejects is answered invalid_message, an unexpected failure internal_error")
    void handlerFailuresKeepTheRouterAnswers() throws Exception {
        worker = new SerialRequestWorker(4);
        CloudRequestHandler wrapped = worker.wrap(new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel reply) {
                if (ID_A.equals(request.id())) {
                    throw new InvalidFrameException("invalid_message", request.id(), "bad path");
                }
                throw new IllegalStateException("disk gone");
            }
        });
        wrapped.handle(request(ID_A), channel);
        wrapped.handle(request(ID_B), channel);
        waitFor(() -> channel.frames().size() == 2);
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("invalid_message");
        assertThat(channel.frames().get(1).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("internal_error");
        assertThat(channel.frames().get(1).toString()).doesNotContain("disk gone");
    }

    @Test
    @DisplayName("closing drops the requests that had not started and refuses new ones without a reply")
    void closeDropsQueuedRequests() throws Exception {
        worker = new SerialRequestWorker(4);
        CloudRequestHandler wrapped = worker.wrap(blocking);
        wrapped.handle(request(ID_A), channel);
        waitFor(() -> !threads.isEmpty());
        wrapped.handle(request(ID_B), channel);
        worker.close();
        wrapped.handle(request(ID_C), channel);
        release.countDown();
        waitFor(() -> handled.size() == 1);
        Thread.sleep(200);
        assertThat(handled).containsExactly(ID_A);
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(ID_A);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }
}

package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ultikits.ultitools.utils.AgentConnection.Disposition;
import com.ultikits.ultitools.utils.AgentConnection.ReconnectOutcome;
import com.ultikits.ultitools.utils.AgentConnection.State;

/**
 * Unit tests of the reconnect state machine with a fake socket factory, a manual scheduler driven
 * by a fake clock, and a capturing log sink. With the random source at the top of its range every
 * delay equals its class's ceiling, so the tests can tell normal (1 s, 2 s, ...) from long (60 s,
 * 120 s, ...) delays exactly.
 */
@DisplayName("AgentConnection state machine")
class AgentConnectionTest {

    private static final String BEARER = "ucs_SECRET123";
    private static final URI ENDPOINT = URI.create("wss://api.example.test/v1/agent");
    private static final double HIGH = Math.nextDown(1.0);
    private static final String BODY_402 = "{\"error\":{\"code\":\"plan_limit\",\"message\":\"paused\","
            + "\"requestId\":\"req_01\",\"details\":{\"reason\":\"pro_expired_over_limit\","
            + "\"graceEndedAt\":\"2026-11-01T00:00:00.000Z\","
            + "\"manageUrl\":\"https://panel.ultikits.com/owner/billing\"}}}";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ManualScheduler scheduler = new ManualScheduler(now);
    private final FakeSockets sockets = new FakeSockets();
    private final AgentConnectionLoopbackTest.Recorder recorder = new AgentConnectionLoopbackTest.Recorder();
    private final List<String> logs = new ArrayList<>();
    private final AtomicReference<String> bearer = new AtomicReference<>(BEARER);
    private AgentConnection connection;

    @BeforeEach
    void setUp() {
        connection = build(() -> HIGH, AgentConnection.Timing.defaults());
    }

    private AgentConnection build(DoubleSupplier random, AgentConnection.Timing timing) {
        return new AgentConnection(ENDPOINT, bearer::get, "6.3.1", scheduler, recorder, sockets,
                new ReconnectPolicy(random), now::get, timing, (level, message) -> logs.add(level.getName() + " " + message));
    }

    // ------------------------------------------------------------------ fakes

    /** A ScheduledExecutorService that runs tasks only when the test drives the fake clock. */
    static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private final AtomicLong now;
        private final List<Task> tasks = new ArrayList<>();
        private long seq;
        private boolean shutdown;

        ManualScheduler(AtomicLong now) {
            this.now = now;
        }

        final class Task implements ScheduledFuture<Object> {
            final long due;
            final long order;
            final Runnable runnable;
            boolean cancelled;
            boolean done;

            Task(long due, long order, Runnable runnable) {
                this.due = due;
                this.order = order;
                this.runnable = runnable;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return unit.convert(due - now.get(), TimeUnit.MILLISECONDS);
            }

            @Override
            public int compareTo(Delayed o) {
                return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (done) {
                    return false;
                }
                cancelled = true;
                return true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                return done || cancelled;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("shut down");
            }
            Task t = new Task(now.get() + unit.toMillis(Math.max(0, delay)), seq++, command);
            tasks.add(t);
            return t;
        }

        @Override
        public void execute(Runnable command) {
            schedule(command, 0, TimeUnit.MILLISECONDS);
        }

        private Task next(long limit) {
            Task best = null;
            for (Task t : tasks) {
                if (t.cancelled || t.done || t.due > limit) {
                    continue;
                }
                if (best == null || t.due < best.due || (t.due == best.due && t.order < best.order)) {
                    best = t;
                }
            }
            return best;
        }

        /** Runs every task due now, including tasks those tasks submit for now. */
        void runDue() {
            advance(0);
        }

        /** Moves the clock forward, running each task at its due time. */
        void advance(long millis) {
            long target = now.get() + millis;
            Task t;
            while ((t = next(target)) != null) {
                if (t.due > now.get()) {
                    now.set(t.due);
                }
                t.done = true;
                t.runnable.run();
            }
            now.set(target);
        }

        int pending() {
            int n = 0;
            for (Task t : tasks) {
                if (!t.cancelled && !t.done) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** One fake socket: records what the state machine did to it and lets the test fire events. */
    final class FakeSocket implements AgentConnection.Handle {
        final Map<String, String> headers;
        final AgentConnection.SocketEvents events;
        final List<String> sent = new ArrayList<>();
        int closedWith;
        boolean open;

        FakeSocket(Map<String, String> headers, AgentConnection.SocketEvents events) {
            this.headers = headers;
            this.events = events;
        }

        @Override
        public void closeAttempt(int code, String reason) {
            closedWith = code;
            open = false;
        }

        @Override
        public boolean sendText(String text) {
            if (!open) {
                return false;
            }
            sent.add(text);
            return true;
        }

        void accept() {
            open = true;
            events.onOpen();
            scheduler.runDue();
        }

        void refuse(int status) {
            refuse(UpgradeRejection.statusOnly(status));
        }

        void refuse(UpgradeRejection rejection) {
            events.onUpgradeRefused(rejection.status(), rejection);
            scheduler.runDue();
        }

        void close(int code) {
            open = false;
            events.onClosed(code, "", true);
            scheduler.runDue();
        }

        void text(String text) {
            events.onText(text);
            scheduler.runDue();
        }
    }

    final class FakeSockets implements AgentConnection.SocketFactory {
        final List<FakeSocket> opened = new ArrayList<>();

        @Override
        public AgentConnection.Handle open(URI endpoint, Map<String, String> headers, AgentConnection.SocketEvents events) {
            FakeSocket s = new FakeSocket(headers, events);
            opened.add(s);
            return s;
        }

        FakeSocket last() {
            return opened.get(opened.size() - 1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private FakeSocket startAndOpen() {
        connection.start();
        sockets.last().accept();
        return sockets.last();
    }

    private long pendingDelay() {
        long at = connection.nextRetryAtMillis();
        return at == 0 ? -1 : at - now.get();
    }

    /** Runs the pending retry; the new attempt's socket is then {@code sockets.last()}. */
    private FakeSocket fireRetry() {
        int before = sockets.opened.size();
        scheduler.advance(pendingDelay());
        assertThat(sockets.opened).hasSize(before + 1);
        return sockets.last();
    }

    private static UpgradeRejection rejection(int status, String retryAfter, String body) {
        Map<String, String> headers = new HashMap<>();
        if (retryAfter != null) {
            headers.put("retry-after", retryAfter);
        }
        return UpgradeRejection.parse(status, headers, body);
    }

    // ------------------------------------------------------------------ basic flow

    @Test
    @DisplayName("IDLE -> CONNECTING -> OPEN, and the socket gets exactly the three upgrade headers")
    void connectsWithTheThreeHeaders() {
        assertThat(connection.state()).isEqualTo(State.IDLE);
        connection.start();
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        FakeSocket s = sockets.last();
        assertThat(s.headers).isEqualTo(connection.buildHeaders(BEARER)).hasSize(3)
                .containsEntry("Authorization", "Bearer " + BEARER)
                .containsEntry("X-UltiCloud-Protocol", "2")
                .containsEntry("X-UltiCloud-Framework", "6.3.1");
        s.accept();
        assertThat(connection.state()).isEqualTo(State.OPEN);
        assertThat(recorder.states).containsExactly(State.CONNECTING, State.OPEN);
        assertThat(recorder.events).containsExactly("opened");
    }

    @Test
    @DisplayName("every attempt opens a new socket with the bearer read at that moment")
    void everyAttemptReadsTheBearerAgain() {
        startAndOpen().close(1011);
        bearer.set("ucs_ROTATED");
        FakeSocket second = fireRetry();
        assertThat(second.headers).containsEntry("Authorization", "Bearer ucs_ROTATED").hasSize(3);
        assertThat(sockets.opened.get(0)).isNotSameAs(second);
    }

    @Test
    @DisplayName("without markEstablished consecutive failures grow the delay; with it the next failure waits attempt 0")
    void markEstablishedResetsTheNormalCounter() {
        startAndOpen().close(1011);
        assertThat(pendingDelay()).isEqualTo(1000);
        fireRetry().accept();
        sockets.last().close(1011);
        assertThat(pendingDelay()).isEqualTo(2000);
        fireRetry().accept();
        connection.markEstablished();
        sockets.last().close(1011);
        assertThat(pendingDelay()).isEqualTo(1000);
    }

    @Test
    @DisplayName("consecutive refusals grow the normal delay 1 s, 2 s, 4 s")
    void consecutiveRefusalsGrow() {
        connection.start();
        sockets.last().refuse(500);
        assertThat(pendingDelay()).isEqualTo(1000);
        fireRetry().refuse(502);
        assertThat(pendingDelay()).isEqualTo(2000);
        fireRetry().refuse(404);
        assertThat(pendingDelay()).isEqualTo(4000);
    }

    // ------------------------------------------------------------------ the provisional table

    @ParameterizedTest(name = "refusal {0} -> {1}")
    @CsvSource({
            "401, STOP_DELETE_CREDENTIAL",
            "426, STOP_KEEP_CREDENTIAL",
            "402, PLAN_LIMIT",
            "429, RETRY_LONG",
            "403, RETRY",
            "404, RETRY",
            "500, RETRY",
            "502, RETRY"
    })
    void refusalTable(int status, Disposition expected) {
        assertThat(AgentConnection.forRefusal(status)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "close {0} -> {1}")
    @CsvSource({
            "4401, STOP_DELETE_CREDENTIAL",
            "4426, STOP_KEEP_CREDENTIAL",
            "4402, PLAN_LIMIT",
            "4000, RETRY_LONG",
            "4400, RETRY_LONG",
            "4429, RETRY_LONG",
            "4408, RETRY",
            "1000, RETRY",
            "1001, RETRY",
            "1006, RETRY",
            "1009, RETRY",
            "1011, RETRY",
            "4999, RETRY"
    })
    void closeTable(int code, Disposition expected) {
        assertThat(AgentConnection.forClose(code)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "refusal {0} -> {1}, retry in {2} ms")
    @CsvSource({
            "401, CREDENTIAL_REJECTED, -1",
            "426, UPGRADE_REQUIRED, -1",
            "402, PLAN_LIMITED, 900000",
            "429, BACKOFF, 60000",
            "403, BACKOFF, 1000",
            "404, BACKOFF, 1000",
            "500, BACKOFF, 1000",
            "502, BACKOFF, 1000"
    })
    void refusalReachesItsState(int status, State expected, long delay) {
        connection.start();
        sockets.last().refuse(status);
        assertThat(connection.state()).isEqualTo(expected);
        assertThat(pendingDelay()).isEqualTo(delay);
        assertThat(recorder.count("credentialRejected")).isEqualTo(status == 401 ? 1 : 0);
        assertThat(sockets.opened).hasSize(1);
    }

    @ParameterizedTest(name = "close {0} -> {1}, retry in {2} ms")
    @CsvSource({
            "4401, CREDENTIAL_REJECTED, -1",
            "4426, UPGRADE_REQUIRED, -1",
            "4402, PLAN_LIMITED, 1000",
            "4000, BACKOFF, 60000",
            "4400, BACKOFF, 60000",
            "4429, BACKOFF, 60000",
            "4408, BACKOFF, 1000",
            "1000, BACKOFF, 1000",
            "1001, BACKOFF, 1000",
            "1006, BACKOFF, 1000",
            "1009, BACKOFF, 1000",
            "1011, BACKOFF, 1000",
            "4999, BACKOFF, 1000"
    })
    void closeReachesItsState(int code, State expected, long delay) {
        startAndOpen().close(code);
        assertThat(connection.state()).isEqualTo(expected);
        assertThat(pendingDelay()).isEqualTo(delay);
        assertThat(recorder.count("credentialRejected")).isEqualTo(code == 4401 ? 1 : 0);
        assertThat(recorder.count("planLimited")).isZero();
    }

    @Test
    @DisplayName("401 reports its request id; 4401 reports none")
    void credentialCallbacks() {
        connection.start();
        sockets.last().refuse(rejection(401, null,
                "{\"error\":{\"code\":\"invalid_credential\",\"message\":\"m\",\"requestId\":\"req_01\"}}"));
        assertThat(recorder.events).containsExactly("credentialRejected 401 req_01");
        connection.start();
        sockets.last().accept();
        sockets.last().close(4401);
        assertThat(recorder.events).containsExactly("credentialRejected 401 req_01", "opened",
                "credentialRejected 4401 null");
    }

    @Test
    @DisplayName("426 reports the minimum framework; 4426 reports none; no credential callback")
    void upgradeCallbacks() {
        connection.start();
        sockets.last().refuse(rejection(426, null, "{\"error\":{\"code\":\"upgrade_required\",\"message\":\"m\","
                + "\"requestId\":\"req_01\",\"details\":{\"supportedProtocols\":[2],\"minFramework\":\"6.3.1\"}}}"));
        assertThat(recorder.events).containsExactly("upgradeRequired 6.3.1 req_01");
        connection.start();
        sockets.last().accept();
        sockets.last().close(4426);
        assertThat(recorder.events).endsWith("upgradeRequired null null");
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @ParameterizedTest(name = "429 with Retry-After {0} waits {1} ms")
    @CsvSource({
            "120, 120000",
            ", 60000",
            "30, 60000",
            "99999, 3600000",
            "Wed 21 Oct 2026 07:28:00 GMT, 60000"
    })
    void tooManyRequestsWaitsAtLeastRetryAfter(String retryAfter, long expected) {
        connection.start();
        sockets.last().refuse(rejection(429, retryAfter,
                "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\",\"requestId\":\"req_01\"}}"));
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(pendingDelay()).isEqualTo(expected);
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("consecutive long-class failures grow the long delay; markEstablished resets it")
    void longDelayGrowsAndResets() {
        startAndOpen().close(4400);
        assertThat(pendingDelay()).isEqualTo(60000);
        fireRetry().accept();
        sockets.last().close(4429);
        assertThat(pendingDelay()).isEqualTo(120000);
        fireRetry().refuse(429);
        assertThat(pendingDelay()).isEqualTo(240000);
        fireRetry().accept();
        connection.markEstablished();
        sockets.last().close(4400);
        assertThat(pendingDelay()).isEqualTo(60000);
    }

    // ------------------------------------------------------------------ plan limit

    @Test
    @DisplayName("402: one notice per episode, fixed 15-minute cadence, silent repeats, one restored line")
    void planLimitEpisode() {
        connection.start();
        sockets.last().refuse(500);
        fireRetry().refuse(500);
        fireRetry().refuse(rejection(402, null, BODY_402));
        assertThat(connection.state()).isEqualTo(State.PLAN_LIMITED);
        assertThat(recorder.planNotices).hasSize(1);
        UpgradeRejection notice = recorder.planNotices.get(0);
        assertThat(notice.planReason()).isEqualTo("pro_expired_over_limit");
        assertThat(notice.graceEndedAt()).isEqualTo("2026-11-01T00:00:00.000Z");
        assertThat(notice.manageUrl()).isEqualTo("https://panel.ultikits.com/owner/billing");
        assertThat(pendingDelay()).isEqualTo(900000);
        assertThat(connection.nextRetryAtMillis()).isEqualTo(now.get() + 900000);
        assertThat(logs).contains("INFO UltiCloud: connection attempt 3 failed (HTTP 402 plan_limit); retrying in 900 s.");

        logs.clear();
        fireRetry().refuse(rejection(402, null, BODY_402));
        fireRetry().refuse(rejection(402, null, BODY_402));
        assertThat(connection.state()).isEqualTo(State.PLAN_LIMITED);
        assertThat(pendingDelay()).isEqualTo(900000);
        assertThat(recorder.planNotices).hasSize(1);
        assertThat(logs).isNotEmpty().allMatch(line -> line.startsWith("FINE "));

        logs.clear();
        fireRetry().accept();
        assertThat(connection.state()).isEqualTo(State.OPEN);
        assertThat(logs).filteredOn(line -> line.startsWith("INFO "))
                .containsExactly("INFO UltiCloud: the plan limit no longer applies; this server is connected again.");
        sockets.last().close(1011);
        assertThat(pendingDelay()).as("counters reset on recovery").isEqualTo(1000);
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("a 401 while plan-limited ends in CREDENTIAL_REJECTED")
    void credentialRejectedWhilePlanLimited() {
        connection.start();
        sockets.last().refuse(rejection(402, null, BODY_402));
        fireRetry().refuse(401);
        assertThat(connection.state()).isEqualTo(State.CREDENTIAL_REJECTED);
        assertThat(pendingDelay()).isEqualTo(-1);
    }

    @Test
    @DisplayName("close 4402: one probe after the attempt-0 delay; its 402 notifies once and starts the cadence")
    void close4402ProbesThenNotifies() {
        startAndOpen().close(4402);
        assertThat(connection.state()).isEqualTo(State.PLAN_LIMITED);
        assertThat(recorder.planNotices).isEmpty();
        assertThat(pendingDelay()).isEqualTo(1000);
        fireRetry().refuse(rejection(402, null, BODY_402));
        assertThat(recorder.planNotices).hasSize(1);
        assertThat(connection.state()).isEqualTo(State.PLAN_LIMITED);
        assertThat(pendingDelay()).isEqualTo(900000);
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("close 4402 whose probe fails another way returns to the normal table without a notice")
    void close4402ProbeFailingOtherwise() {
        startAndOpen().close(4402);
        fireRetry().close(1006);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(recorder.planNotices).isEmpty();
        assertThat(pendingDelay()).isBetween(1000L, 60000L);
    }

    // ------------------------------------------------------------------ reconnectNow

    @Test
    @DisplayName("reconnectNow from BACKOFF opens at once and cancels the pending retry")
    void reconnectNowFromBackoff() {
        connection.start();
        sockets.last().refuse(500);
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.STARTED);
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        assertThat(sockets.opened).hasSize(2);
        assertThat(connection.nextRetryAtMillis()).isZero();
        scheduler.advance(5000);
        assertThat(sockets.opened).hasSize(2);
    }

    @Test
    @DisplayName("reconnectNow from PLAN_LIMITED stays in the same episode on a renewed 402")
    void reconnectNowFromPlanLimited() {
        connection.start();
        sockets.last().refuse(rejection(402, null, BODY_402));
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.STARTED);
        assertThat(sockets.opened).hasSize(2);
        sockets.last().refuse(rejection(402, null, BODY_402));
        assertThat(connection.state()).isEqualTo(State.PLAN_LIMITED);
        assertThat(recorder.planNotices).hasSize(1);
    }

    @Test
    @DisplayName("reconnectNow from SUPERSEDED restarts with the counters reset")
    void reconnectNowFromSuperseded() {
        supersedeThreeTimes();
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.STARTED);
        sockets.last().accept();
        sockets.last().close(4000);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(pendingDelay()).isEqualTo(60000);
    }

    @Test
    @DisplayName("reconnectNow reports why it did nothing and never opens a socket then")
    void reconnectNowOutcomes() {
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.NOT_RUNNING);
        connection.start();
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.ALREADY_CONNECTING);
        sockets.last().accept();
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.ALREADY_OPEN);
        sockets.last().close(4426);
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.UPGRADE_REQUIRED);
        connection.start();
        sockets.last().refuse(401);
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.NOT_RUNNING);
        connection.stop();
        assertThat(connection.reconnectNow()).isEqualTo(ReconnectOutcome.NOT_RUNNING);
        assertThat(sockets.opened).hasSize(2);
    }

    // ------------------------------------------------------------------ supersession

    private void supersedeThreeTimes() {
        startAndOpen().close(4000);
        fireRetry().accept();
        sockets.last().close(4000);
        fireRetry().accept();
        sockets.last().close(4000);
    }

    @Test
    @DisplayName("three consecutive 4000 closes end in SUPERSEDED, each earlier one warns and backs off long")
    void threeSupersessionsStop() {
        startAndOpen().close(4000);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(pendingDelay()).isEqualTo(60000);
        assertThat(logs).contains("WARNING UltiCloud: another connection is using this server's credential and "
                + "replaced this one (close 4000); retrying in 60 s.");
        fireRetry().accept();
        sockets.last().close(4000);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(pendingDelay()).isEqualTo(120000);
        fireRetry().accept();
        sockets.last().close(4000);
        assertThat(connection.state()).isEqualTo(State.SUPERSEDED);
        assertThat(recorder.count("superseded")).isEqualTo(1);
        assertThat(pendingDelay()).isEqualTo(-1);
        scheduler.advance(3_600_000);
        assertThat(sockets.opened).hasSize(3);
        assertThat(recorder.count("credentialRejected")).isZero();
    }

    @Test
    @DisplayName("a connection that stayed open for 10 minutes resets the 4000 count to one")
    void longLivedConnectionResetsSupersession() {
        startAndOpen().close(4000);
        fireRetry().accept();
        scheduler.advance(600_000);
        sockets.last().close(4000);
        fireRetry().accept();
        sockets.last().close(4000);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        fireRetry().accept();
        sockets.last().close(4000);
        assertThat(connection.state()).isEqualTo(State.SUPERSEDED);
    }

    // ------------------------------------------------------------------ stop / start / send

    @Test
    @DisplayName("stop from CONNECTING, OPEN, BACKOFF and terminal states; later events are ignored")
    void stopFromEveryState() {
        connection.start();
        FakeSocket connecting = sockets.last();
        connection.stop();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(connecting.closedWith).isEqualTo(1000);
        connecting.accept();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(recorder.events).doesNotContain("opened");

        FakeSocket open = startAndOpen();
        connection.stop();
        assertThat(open.closedWith).isEqualTo(1000);
        open.close(1011);
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(pendingDelay()).isEqualTo(-1);

        connection.start();
        sockets.last().refuse(500);
        int before = sockets.opened.size();
        connection.stop();
        scheduler.advance(3_600_000);
        assertThat(sockets.opened).hasSize(before);
        assertThat(connection.state()).isEqualTo(State.STOPPED);

        connection.start();
        sockets.last().refuse(401);
        connection.stop();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        connection.start();
        sockets.last().refuse(426);
        connection.stop();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        supersedeThreeTimes();
        connection.stop();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
    }

    @Test
    @DisplayName("start from a terminal state starts a fresh cycle; start while active is a no-op")
    void startSemantics() {
        connection.start();
        connection.start();
        assertThat(sockets.opened).hasSize(1);
        sockets.last().refuse(500);
        connection.start();
        assertThat(sockets.opened).hasSize(1);
        fireRetry().refuse(500);
        fireRetry().accept();
        connection.start();
        assertThat(sockets.opened).hasSize(3);
        sockets.last().close(4401);

        logs.clear();
        connection.start();
        assertThat(logs).containsExactly("INFO UltiCloud: connecting to " + ENDPOINT + " (attempt 1).");
        sockets.last().refuse(500);
        assertThat(pendingDelay()).as("counters reset by a fresh cycle").isEqualTo(1000);
    }

    @Test
    @DisplayName("start without a credential goes to STOPPED without a socket")
    void startWithoutCredential() {
        bearer.set(null);
        connection.start();
        assertThat(connection.state()).isEqualTo(State.STOPPED);
        assertThat(sockets.opened).isEmpty();
    }

    @Test
    @DisplayName("send returns false unless OPEN")
    void sendOnlyWhenOpen() {
        assertThat(connection.send("x")).isFalse();
        connection.start();
        assertThat(connection.send("x")).isFalse();
        sockets.last().accept();
        assertThat(connection.send("x")).isTrue();
        assertThat(sockets.last().sent).containsExactly("x");
        sockets.last().close(1011);
        assertThat(connection.send("y")).isFalse();
    }

    @Test
    @DisplayName("onStateChanged fires once per real transition, never for a no-op")
    void stateChangesOncePerTransition() {
        startAndOpen();
        connection.start();
        connection.markEstablished();
        sockets.last().close(1011);
        fireRetry().refuse(426);
        connection.stop();
        connection.stop();
        assertThat(recorder.states).containsExactly(State.CONNECTING, State.OPEN, State.BACKOFF,
                State.CONNECTING, State.UPGRADE_REQUIRED, State.STOPPED);
    }

    // ------------------------------------------------------------------ log lines

    @Test
    @DisplayName("one attempt line per attempt, one retry line per retry, delays rounded up, none after stop")
    void logLines() {
        connection = build(() -> 0.5, AgentConnection.Timing.defaults());
        connection.start();
        sockets.last().refuse(500);
        FakeSocket second = fireRetry();
        second.refuse(rejection(403, null, "{\"error\":{\"code\":\"forbidden\",\"message\":\"m\",\"requestId\":\"r\"}}"));
        fireRetry().accept();
        sockets.last().close(1011);
        assertThat(logs).containsExactly(
                "INFO UltiCloud: connecting to " + ENDPOINT + " (attempt 1).",
                "INFO UltiCloud: connection attempt 1 failed (HTTP 500); retrying in 1 s.",
                "INFO UltiCloud: connecting to " + ENDPOINT + " (attempt 2).",
                "INFO UltiCloud: connection attempt 2 failed (HTTP 403 forbidden); retrying in 2 s.",
                "INFO UltiCloud: connecting to " + ENDPOINT + " (attempt 3).",
                "INFO UltiCloud: connection attempt 3 failed (closed 1011); retrying in 3 s.");

        int beforeStop = logs.size();
        connection.stop();
        sockets.last().close(1006);
        scheduler.advance(3_600_000);
        assertThat(logs).hasSize(beforeStop);
        assertThat(logs).noneMatch(line -> line.contains(BEARER));
    }

    // ------------------------------------------------------------------ handshake deadline and heartbeat

    @Test
    @DisplayName("an attempt not OPEN at the 15 s deadline is force-closed and retried with the normal delay")
    void handshakeDeadlineFires() {
        connection.start();
        FakeSocket s = sockets.last();
        scheduler.advance(14_999);
        assertThat(connection.state()).isEqualTo(State.CONNECTING);
        scheduler.advance(1);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(s.closedWith).isEqualTo(1000);
        assertThat(pendingDelay()).isEqualTo(1000);
        assertThat(logs).contains("INFO UltiCloud: connection attempt 1 failed (no handshake response within 15 s); "
                + "retrying in 1 s.");
        s.close(1006);
        assertThat(connection.state()).as("the abandoned socket's close is stale").isEqualTo(State.BACKOFF);
        assertThat(pendingDelay()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a deadline for an attempt that already opened or failed changes nothing")
    void staleDeadlineChangesNothing() {
        startAndOpen();
        scheduler.advance(20_000);
        assertThat(connection.state()).isEqualTo(State.OPEN);
        sockets.last().close(1011);
        long retryAt = connection.nextRetryAtMillis();
        FakeSocket second = fireRetry();
        second.refuse(500);
        long secondRetryAt = connection.nextRetryAtMillis();
        scheduler.advance(secondRetryAt - now.get() - 1);
        assertThat(connection.nextRetryAtMillis()).isEqualTo(secondRetryAt);
        assertThat(retryAt).isLessThan(secondRetryAt);
        assertThat(logs).noneMatch(line -> line.contains("no handshake response"));
    }

    @Test
    @DisplayName("heartbeat: no ping before markEstablished, ping every 30 s after, pong consumed and resets the timeout")
    void heartbeatPingsAndConsumesPong() {
        FakeSocket s = startAndOpen();
        scheduler.advance(60_000);
        assertThat(s.sent).isEmpty();
        connection.markEstablished();
        scheduler.advance(30_000);
        assertThat(s.sent).containsExactly("ping");
        scheduler.advance(30_000);
        assertThat(s.sent).containsExactly("ping", "ping");
        s.text("pong");
        s.text("hello");
        assertThat(recorder.events).contains("text hello").doesNotContain("text pong");
        scheduler.advance(60_000);
        assertThat(connection.state()).as("the pong at 60 s keeps it alive past 90 s").isEqualTo(State.OPEN);
        assertThat(s.sent).hasSize(4);
    }

    @Test
    @DisplayName("heartbeat: no pong for 90 s closes the socket and retries with the normal delay")
    void heartbeatTimeout() {
        FakeSocket s = startAndOpen();
        connection.markEstablished();
        scheduler.advance(89_999);
        assertThat(connection.state()).isEqualTo(State.OPEN);
        assertThat(s.sent).containsExactly("ping", "ping");
        scheduler.advance(1);
        assertThat(connection.state()).isEqualTo(State.BACKOFF);
        assertThat(s.closedWith).isEqualTo(1000);
        assertThat(pendingDelay()).isEqualTo(1000);
        assertThat(logs).contains("INFO UltiCloud: connection attempt 1 failed (no heartbeat reply within 90 s); "
                + "retrying in 1 s.");
    }

    @Test
    @DisplayName("stop cancels the heartbeat")
    void stopCancelsHeartbeat() {
        FakeSocket s = startAndOpen();
        connection.markEstablished();
        scheduler.advance(30_000);
        connection.stop();
        s.open = true;
        scheduler.advance(300_000);
        assertThat(s.sent).containsExactly("ping");
        assertThat(scheduler.pending()).isZero();
    }
}

package com.ultikits.ultitools.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Deterministic stand-ins for the device-login polling seam of {@link CloudSession}: a scheduler
 * that only queues steps, a clock the scheduler advances, a scripted {@link DeviceAuthorizationClient}
 * and a listener that records what it was told. No test using them sleeps.
 */
final class DeviceLoginTestSupport {

    private DeviceLoginTestSupport() {
    }

    /** A grant with the given lifetime and interval; the device code is {@code dc-1}. */
    static DeviceAuthorizationClient.Grant grant(long expiresInSeconds, long intervalSeconds) {
        return new DeviceAuthorizationClient.Grant("dc-1", "BCDF-GHJK", "https://panel.ultikits.com/device",
                "https://panel.ultikits.com/device?code=BCDF-GHJK", expiresInSeconds, intervalSeconds);
    }

    /** A synthetic, well-formed server credential. */
    static ServerCredential credential(String serverName) {
        return new ServerCredential("ucs_TESTSECRET", "cred-1", "01JTESTSERVERID0000000000", "u-1", serverName,
                1_700_000_000_000L);
    }

    /** A clock that only moves when {@link FakeScheduler#runNext()} advances it. */
    static final class FakeClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    /** Queues every step with its delay; {@link #runNext()} runs the oldest one on the calling thread. */
    static final class FakeScheduler implements CloudSession.DeviceStepScheduler {
        private final FakeClock clock;
        private final Deque<Step> queue = new ArrayDeque<>();
        private final List<Long> delays = new ArrayList<>();

        FakeScheduler(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable step, long delayMillis) {
            Step queued = new Step(step, delayMillis);
            queue.add(queued);
            delays.add(delayMillis);
            return queued;
        }

        /** @return the number of queued, not cancelled steps */
        synchronized int pending() {
            int count = 0;
            for (Step step : queue) {
                if (!step.cancelled) {
                    count++;
                }
            }
            return count;
        }

        /** @return every delay ever requested, in order */
        synchronized List<Long> delays() {
            return new ArrayList<>(delays);
        }

        /** Advances the clock by the oldest live step's delay and runs it. */
        void runNext() {
            Step next;
            synchronized (this) {
                next = queue.poll();
                while (next != null && next.cancelled) {
                    next = queue.poll();
                }
            }
            if (next == null) {
                throw new IllegalStateException("no step is scheduled");
            }
            clock.advance(next.delayMillis);
            next.done = true;
            next.task.run();
        }
    }

    /** One queued step; also its own {@link ScheduledFuture}. */
    static final class Step implements ScheduledFuture<Object> {
        private final Runnable task;
        private final long delayMillis;
        private volatile boolean cancelled;
        private volatile boolean done;

        Step(Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return !done;
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

    /** Answers {@link #authorize} with one result and each {@link #pollToken} with the next scripted one. */
    static final class ScriptedClient extends DeviceAuthorizationClient {
        private final DeviceAuthorizationClient.AuthorizeResult authorizeResult;
        private final Deque<Supplier<PollResult>> polls = new ArrayDeque<>();
        private int pollCount;

        ScriptedClient(DeviceAuthorizationClient.AuthorizeResult authorizeResult) {
            this.authorizeResult = authorizeResult;
        }

        ScriptedClient thenPoll(Supplier<PollResult> next) {
            polls.add(next);
            return this;
        }

        ScriptedClient thenPoll(PollResult next) {
            return thenPoll(() -> next);
        }

        @Override
        AuthorizeResult authorize(String frameworkVersion, String suggestedNameOrNull, String attachToServerIdOrNull) {
            return authorizeResult;
        }

        @Override
        synchronized PollResult pollToken(String deviceCode) {
            pollCount++;
            Supplier<PollResult> next = polls.poll();
            return next == null ? PollResult.of(PollResult.Kind.PENDING) : next.get();
        }

        synchronized int pollCount() {
            return pollCount;
        }
    }

    /** Records every callback as a short line. */
    static final class RecordingListener implements CloudAuthManager.DeviceLoginListener {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onAlreadyLinked(String serverName) {
            events.add("alreadyLinked:" + serverName);
        }

        @Override
        public void onRateLimited(long seconds) {
            events.add("rateLimited:" + seconds);
        }

        @Override
        public void onRequesting() {
            events.add("requesting");
        }

        @Override
        public void onCode(String verificationUri, String userCode, String verificationUriComplete,
                long expiresInSeconds) {
            events.add("code:" + userCode);
        }

        @Override
        public void onRequestFailed(String reason) {
            events.add("failed:" + reason);
        }

        @Override
        public void onLinked(String serverName, String serverId) {
            events.add("linked:" + serverName);
        }

        @Override
        public void onDenied() {
            events.add("denied");
        }

        @Override
        public void onExpired() {
            events.add("expired");
        }

        @Override
        public void onRejected(String code, String message) {
            events.add("rejected:" + code + ":" + message);
        }
    }
}

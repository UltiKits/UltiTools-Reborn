package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** ULIDs for protocol v2 envelope ids (D-11, D-21), checked against the ULID specification. */
@DisplayName("Ulid -- Crockford base32, time-ordered, monotonic, thread-safe")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class UlidTest {

    /** The specification's example timestamp and its encoded time part. */
    private static final long SPEC_TIME = 1_469_918_176_385L;
    private static final String SPEC_TIME_PART = "01ARYZ6S41";

    @Test
    @DisplayName("the time part of a ULID made at the specification's example time is 01ARYZ6S41")
    void timePartMatchesTheSpecificationVector() {
        String id = new Ulid(() -> SPEC_TIME, new Random(1)).nextValue();

        assertThat(id).hasSize(Ulid.LENGTH).startsWith(SPEC_TIME_PART);
        assertThat(Ulid.isValid(id)).isTrue();
        assertThat(Ulid.timestampOf(id)).isEqualTo(SPEC_TIME);
    }

    @Test
    @DisplayName("10 000 ids at a frozen clock are unique and strictly increasing")
    void frozenClockIdsAreStrictlyIncreasing() {
        Ulid generator = new Ulid(() -> SPEC_TIME, new Random(7));
        String previous = generator.nextValue();
        Set<String> all = new HashSet<>();
        all.add(previous);
        for (int i = 0; i < 9_999; i++) {
            String id = generator.nextValue();
            assertThat(id.compareTo(previous)).as("id %d sorts after the previous one", i).isPositive();
            previous = id;
            all.add(id);
        }
        assertThat(all).hasSize(10_000);
    }

    @Test
    @DisplayName("ids sort by creation time, and a clock that steps back does not break the order")
    void idsSortByTimeAndStayMonotonicWhenTheClockStepsBack() {
        AtomicLong clock = new AtomicLong(SPEC_TIME);
        Ulid generator = new Ulid(clock::get, new Random(3));
        String first = generator.nextValue();
        clock.set(SPEC_TIME + 1);
        String second = generator.nextValue();
        clock.set(SPEC_TIME - 1_000);
        String third = generator.nextValue();

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(Ulid.timestampOf(second)).isEqualTo(SPEC_TIME + 1);
    }

    @Test
    @DisplayName("an 80-bit random overflow waits for the next millisecond")
    void randomOverflowWaitsForTheNextMillisecond() {
        AtomicLong clock = new AtomicLong(SPEC_TIME);
        AtomicLong calls = new AtomicLong();
        Random allOnes = new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public int nextInt() {
                return -1;
            }

            @Override
            public long nextLong() {
                return -1L;
            }
        };
        Ulid generator = new Ulid(() -> {
            // The clock advances only after the generator has asked for it a few times while waiting.
            if (calls.incrementAndGet() > 3) {
                clock.set(SPEC_TIME + 1);
            }
            return clock.get();
        }, allOnes);

        String max = generator.nextValue();
        String next = generator.nextValue();

        assertThat(max).isEqualTo(SPEC_TIME_PART + "ZZZZZZZZZZZZZZZZ");
        assertThat(Ulid.timestampOf(next)).isEqualTo(SPEC_TIME + 1);
        assertThat(next).isGreaterThan(max);
    }

    @Test
    @DisplayName("8 threads drawing 10 000 ids each from next() get 80 000 unique ids, each thread's increasing")
    void sharedGeneratorIsThreadSafe() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<String>> draw = () -> {
                    start.await();
                    List<String> ids = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        ids.add(Ulid.next());
                    }
                    return ids;
                };
                futures.add(pool.submit(draw));
            }
            start.countDown();
            Set<String> all = new HashSet<>();
            for (Future<List<String>> future : futures) {
                List<String> ids = future.get(30, TimeUnit.SECONDS);
                for (int i = 1; i < ids.size(); i++) {
                    assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
                }
                all.addAll(ids);
            }
            assertThat(all).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("isValid: 26 Crockford characters, case-insensitive, no I L O U, within range")
    void isValidFollowsTheSpecification() {
        String id = Ulid.next();

        assertThat(Ulid.isValid(id)).isTrue();
        assertThat(Ulid.isValid(id.toLowerCase(java.util.Locale.ROOT))).as("lowercase is accepted").isTrue();
        assertThat(Ulid.isValid(id.substring(1))).as("25 characters").isFalse();
        assertThat(Ulid.isValid(id + "0")).as("27 characters").isFalse();
        assertThat(Ulid.isValid(null)).isFalse();
        for (char excluded : new char[] {'I', 'L', 'O', 'U', 'i', 'l', 'o', 'u'}) {
            assertThat(Ulid.isValid(excluded + id.substring(1))).as("%s is not Crockford base32", excluded).isFalse();
        }
        assertThat(Ulid.isValid("7ZZZZZZZZZZZZZZZZZZZZZZZZZ")).as("the largest ULID").isTrue();
        assertThat(Ulid.isValid("8ZZZZZZZZZZZZZZZZZZZZZZZZZ")).as("beyond 128 bits").isFalse();
        assertThat(Ulid.isValid("01ARYZ6S41TSV4RRFFQ69G5FA-")).as("punctuation").isFalse();
    }

    @Test
    @DisplayName("timestampOf round-trips and rejects a non-ULID")
    void timestampOfRoundTrips() {
        long now = 1_760_000_123_456L;
        String id = new Ulid(() -> now, new Random(5)).nextValue();

        assertThat(Ulid.timestampOf(id)).isEqualTo(now);
        assertThat(Ulid.timestampOf(id.toLowerCase(java.util.Locale.ROOT))).isEqualTo(now);
        assertThatThrownBy(() -> Ulid.timestampOf("not-a-ulid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a clock outside the 48-bit range is refused")
    void clockOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> new Ulid(() -> -1L, new Random()).nextValue())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Ulid(() -> 1L << 48, new Random()).nextValue())
                .isInstanceOf(IllegalStateException.class);
    }
}

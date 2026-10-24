package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ReconnectPolicy full-jitter bounds")
class ReconnectPolicyTest {

    private static final double LOW = 0.0;
    private static final double HIGH = Math.nextDown(1.0);

    @ParameterizedTest(name = "normal attempt {0} -> [{1}, {2}]")
    @CsvSource({
            "0, 1000, 1000",
            "1, 1000, 2000",
            "2, 1000, 4000",
            "5, 1000, 32000",
            "6, 1000, 60000",
            "7, 1000, 60000",
            "63, 1000, 60000",
            "2147483647, 1000, 60000",
            "-1, 1000, 1000"
    })
    void normalBounds(int attempt, long low, long high) {
        assertThat(new ReconnectPolicy(() -> LOW).delayMillis(attempt)).isEqualTo(low);
        assertThat(new ReconnectPolicy(() -> HIGH).delayMillis(attempt)).isEqualTo(high);
    }

    @ParameterizedTest(name = "long failure {0} -> [{1}, {2}]")
    @CsvSource({
            "0, 60000, 60000",
            "1, 60000, 120000",
            "3, 60000, 480000",
            "4, 60000, 900000",
            "5, 60000, 900000",
            "2147483647, 60000, 900000"
    })
    void longBounds(int failures, long low, long high) {
        assertThat(new ReconnectPolicy(() -> LOW).longDelayMillis(failures)).isEqualTo(low);
        assertThat(new ReconnectPolicy(() -> HIGH).longDelayMillis(failures)).isEqualTo(high);
    }

    @Test
    @DisplayName("10 000 random draws never leave either class's bounds")
    void randomSweepStaysInBounds() {
        Random random = new Random(42);
        ReconnectPolicy policy = new ReconnectPolicy(random::nextDouble);
        for (int i = 0; i < 10000; i++) {
            int n = random.nextInt(80);
            assertThat(policy.delayMillis(n)).isBetween(ReconnectPolicy.MIN_DELAY_MS, ReconnectPolicy.MAX_DELAY_MS);
            assertThat(policy.longDelayMillis(n))
                    .isBetween(ReconnectPolicy.LONG_MIN_DELAY_MS, ReconnectPolicy.LONG_MAX_DELAY_MS);
        }
    }

    @Test
    @DisplayName("an out-of-range random source is clamped into the range")
    void outOfRangeRandomIsClamped() {
        assertThat(new ReconnectPolicy(() -> 1.5).delayMillis(3)).isEqualTo(8000);
        assertThat(new ReconnectPolicy(() -> -2).delayMillis(3)).isEqualTo(1000);
        assertThat(new ReconnectPolicy(() -> Double.NaN).longDelayMillis(3)).isEqualTo(60000);
    }
}

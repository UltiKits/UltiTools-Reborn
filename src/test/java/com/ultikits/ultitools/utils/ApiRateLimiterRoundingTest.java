package com.ultikits.ultitools.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #753: the remaining cooldown shown to the operator is rounded up, so a refused caller never
 * reads "0 seconds" and 9.9 s left is not shown as 9.
 */
class ApiRateLimiterRoundingTest {

    @Test
    @DisplayName("a part of a second left is shown as one second")
    void partOfASecondRoundsUpToOne() {
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 59_900L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("9.9 seconds left is shown as 10")
    void ninePointNineSecondsRoundsUpToTen() {
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 50_100L)).isEqualTo(10L);
    }

    @Test
    @DisplayName("whole seconds stay whole, and a passed cooldown is zero")
    void wholeSecondsAndPassedCooldown() {
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 0L)).isEqualTo(60L);
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 59_000L)).isEqualTo(1L);
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 60_000L)).isZero();
        assertThat(ApiRateLimiter.remainingSeconds(0L, 60_000L, 70_000L)).isZero();
    }
}

package com.ultikits.ultitools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TicksTest {
    @Test
    void ceil_roundsUpAtTickAndNanosecondBoundaries() {
        assertThat(Ticks.ceil(Duration.ZERO)).isZero();
        assertThat(Ticks.ceil(Duration.ofNanos(1))).isEqualTo(1);
        assertThat(Ticks.ceil(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(Ticks.ceil(Duration.ofMillis(50))).isEqualTo(1);
        assertThat(Ticks.ceil(Duration.ofMillis(51))).isEqualTo(2);
        assertThat(Ticks.ceil(Duration.ofNanos(1_000_000_001))).isEqualTo(21);
        assertThat(Ticks.TICK).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void of_convertsWithoutIntermediateOverflow() {
        assertThat(Ticks.of(0)).isEqualTo(Duration.ZERO);
        assertThat(Ticks.of(20)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Ticks.of(21)).isEqualTo(Duration.ofMillis(1050));
        assertThat(Ticks.ceil(Ticks.of(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void invalidDurationsAndOverflow_areRejected() {
        assertThatThrownBy(() -> Ticks.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticks.ceil(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticks.ceil(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticks.ceil(Duration.ofSeconds(Long.MAX_VALUE))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Ticks.ceil(Ticks.of(Long.MAX_VALUE).plusNanos(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatNullPointerException().isThrownBy(() -> Ticks.ceil(null)).withMessage("duration");
    }

    @Test
    void retiredException_preservesMessageAndRejectsNull() {
        assertThat(new TaskRetiredException("removed")).hasMessage("removed");
        assertThatNullPointerException().isThrownBy(() -> new TaskRetiredException(null)).withMessage("message");
        assertThat(TaskState.values()).containsExactly(TaskState.SCHEDULED, TaskState.RUNNING,
                TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.RETIRED);
    }
}

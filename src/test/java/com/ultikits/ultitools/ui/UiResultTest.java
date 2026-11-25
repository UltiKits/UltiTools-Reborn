package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UiResultTest {
    @Test
    void completed_returnsAnswerAndValueEquality() {
        UiResult<Boolean> result = new UiResult.Completed<>(false);
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.value()).isFalse();
        assertThat(result).isEqualTo(new UiResult.Completed<>(false));
        assertThat(result.hashCode()).isEqualTo(new UiResult.Completed<>(false).hashCode());
        assertThat(result.toString()).contains("false");
        assertThat(new UiResult.Completed<>(Ack.ACK).value()).isEqualTo(Ack.ACK);
    }

    @Test
    void closed_hasReasonAndNoValue() {
        for (CloseReason reason : CloseReason.values()) {
            UiResult.Closed<String> result = new UiResult.Closed<>(reason);
            assertThat(result.isCompleted()).isFalse();
            assertThat(result.reason()).isSameAs(reason);
            assertThat(result).isEqualTo(new UiResult.Closed<>(reason));
            assertThatThrownBy(result::value).isInstanceOf(IllegalStateException.class).hasMessageContaining(reason.name());
        }
        assertThat(CloseReason.values()).hasSize(8);
        assertThat(ClickKind.values()).hasSize(8);
    }

    @Test
    void constructors_rejectNullValuesAndReasons() {
        assertThatNullPointerException().isThrownBy(() -> new UiResult.Completed<>(null)).withMessage("value");
        assertThatNullPointerException().isThrownBy(() -> new UiResult.Closed<>(null)).withMessage("reason");
    }
}

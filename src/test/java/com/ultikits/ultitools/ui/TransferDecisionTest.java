package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.Text;
import org.junit.jupiter.api.Test;

class TransferDecisionTest {
    @Test void decisions_clampRequestedAmountAndCarryOptionalMessage() {
        assertThat(TransferDecision.allow().allowedAmount(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(TransferDecision.allowAtMost(2).allowedAmount(5)).isEqualTo(2);
        assertThat(TransferDecision.allowAtMost(5).allowedAmount(2)).isEqualTo(2);
        assertThat(TransferDecision.allowAtMost(0).allowedAmount(3)).isZero();
        assertThat(TransferDecision.refuse().allowedAmount(3)).isZero();
        assertThat(TransferDecision.allow().allowedAmount(0)).isZero();
        Text text = Text.plain("No");
        assertThat(TransferDecision.refuse(text).message()).contains(text);
        assertThat(TransferDecision.refuse(text).allowedAmount(99)).isZero();
        assertThat(TransferDecision.allow().message()).isEmpty();
        assertThat(TransferDecision.refuse().message()).isEmpty();
    }
    @Test void decisions_rejectInvalidAmountsAndNullMessage() {
        assertThatThrownBy(() -> TransferDecision.allowAtMost(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransferDecision.allow().allowedAmount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransferDecision.refuse(null)).isInstanceOf(NullPointerException.class);
    }
}

package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(CommandTestExtension.class)
class CommandTestExtensionContractTest {
    @Test
    void unstubbedAsyncSubmissionFailsInsteadOfSilentlyDroppingTheBody() {
        assertThatThrownBy(() -> CommandRuntime.scheduler().async().run(() -> { }))
                .isInstanceOf(AssertionError.class).hasMessageContaining("unstubbed async submission");
    }

    @Test
    void unstubbedAsyncExecutionAlsoFailsInsteadOfSilentlyPassingNegativeAssertions() {
        assertThatThrownBy(() -> CommandRuntime.scheduler().async().execute(() -> { }))
                .isInstanceOf(AssertionError.class).hasMessageContaining("unstubbed async submission");
    }
}

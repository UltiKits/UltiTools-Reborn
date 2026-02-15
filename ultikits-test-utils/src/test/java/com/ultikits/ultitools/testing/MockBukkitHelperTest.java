package com.ultikits.ultitools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("MockBukkitHelper Tests")
class MockBukkitHelperTest {

    @Test
    @DisplayName("ensureCleanState does not throw when MockBukkit is not on classpath")
    void ensureCleanStateWithoutMockBukkit() {
        assertThatCode(MockBukkitHelper::ensureCleanState).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("safeUnmock does not throw when not mocked")
    void safeUnmockWhenNotMocked() {
        assertThatCode(MockBukkitHelper::safeUnmock).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ensureCleanState is idempotent")
    void ensureCleanStateIdempotent() {
        assertThatCode(() -> {
            MockBukkitHelper.ensureCleanState();
            MockBukkitHelper.ensureCleanState();
        }).doesNotThrowAnyException();
    }
}

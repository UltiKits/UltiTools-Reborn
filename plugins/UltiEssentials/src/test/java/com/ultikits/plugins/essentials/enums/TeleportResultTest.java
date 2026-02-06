package com.ultikits.plugins.essentials.enums;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for TeleportResult enum.
 * <p>
 * 测试传送结果枚举类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("TeleportResult Enum Tests")
class TeleportResultTest {

    @Test
    @DisplayName("Should have all expected values")
    void shouldHaveAllValues() {
        TeleportResult[] values = TeleportResult.values();
        assertThat(values).hasSize(8);
        assertThat(values).contains(
            TeleportResult.SUCCESS,
            TeleportResult.WARMUP_STARTED,
            TeleportResult.NOT_FOUND,
            TeleportResult.WORLD_NOT_FOUND,
            TeleportResult.NO_PERMISSION,
            TeleportResult.ALREADY_TELEPORTING,
            TeleportResult.DISABLED,
            TeleportResult.CANCELLED
        );
    }

    @Test
    @DisplayName("Should support valueOf for all values")
    void shouldSupportValueOf() {
        assertThat(TeleportResult.valueOf("SUCCESS")).isEqualTo(TeleportResult.SUCCESS);
        assertThat(TeleportResult.valueOf("WARMUP_STARTED")).isEqualTo(TeleportResult.WARMUP_STARTED);
        assertThat(TeleportResult.valueOf("NOT_FOUND")).isEqualTo(TeleportResult.NOT_FOUND);
        assertThat(TeleportResult.valueOf("WORLD_NOT_FOUND")).isEqualTo(TeleportResult.WORLD_NOT_FOUND);
        assertThat(TeleportResult.valueOf("NO_PERMISSION")).isEqualTo(TeleportResult.NO_PERMISSION);
        assertThat(TeleportResult.valueOf("ALREADY_TELEPORTING")).isEqualTo(TeleportResult.ALREADY_TELEPORTING);
        assertThat(TeleportResult.valueOf("DISABLED")).isEqualTo(TeleportResult.DISABLED);
        assertThat(TeleportResult.valueOf("CANCELLED")).isEqualTo(TeleportResult.CANCELLED);
    }

    @Test
    @DisplayName("Should throw for invalid valueOf")
    void shouldThrowForInvalidValueOf() {
        assertThatThrownBy(() -> TeleportResult.valueOf("INVALID"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Each value should have unique ordinal")
    void eachValueShouldHaveUniqueOrdinal() {
        TeleportResult[] values = TeleportResult.values();
        for (int i = 0; i < values.length; i++) {
            assertThat(values[i].ordinal()).isEqualTo(i);
        }
    }
}

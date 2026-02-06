package com.ultikits.plugins.remotebag.enums;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccessMode Enum Tests")
class AccessModeTest {

    @Test
    @DisplayName("Should have EDIT value")
    void hasEdit() {
        assertThat(AccessMode.EDIT).isNotNull();
    }

    @Test
    @DisplayName("Should have READ_ONLY value")
    void hasReadOnly() {
        assertThat(AccessMode.READ_ONLY).isNotNull();
    }

    @Test
    @DisplayName("Should have exactly 2 values")
    void hasExactlyTwoValues() {
        assertThat(AccessMode.values()).hasSize(2);
    }

    @Test
    @DisplayName("Should contain EDIT and READ_ONLY")
    void containsAllValues() {
        assertThat(AccessMode.values()).containsExactly(
                AccessMode.EDIT,
                AccessMode.READ_ONLY
        );
    }

    @Test
    @DisplayName("valueOf should work for EDIT")
    void valueOfEdit() {
        assertThat(AccessMode.valueOf("EDIT")).isEqualTo(AccessMode.EDIT);
    }

    @Test
    @DisplayName("valueOf should work for READ_ONLY")
    void valueOfReadOnly() {
        assertThat(AccessMode.valueOf("READ_ONLY")).isEqualTo(AccessMode.READ_ONLY);
    }

    @Test
    @DisplayName("valueOf should throw for invalid value")
    void valueOfInvalid() {
        assertThatThrownBy(() -> AccessMode.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.ultikits.plugins.remotebag.enums;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("LockType Enum Tests")
class LockTypeTest {

    @Test
    @DisplayName("Should have OWNER value")
    void hasOwner() {
        assertThat(LockType.OWNER).isNotNull();
    }

    @Test
    @DisplayName("Should have ADMIN value")
    void hasAdmin() {
        assertThat(LockType.ADMIN).isNotNull();
    }

    @Test
    @DisplayName("Should have exactly 2 values")
    void hasExactlyTwoValues() {
        assertThat(LockType.values()).hasSize(2);
    }

    @Test
    @DisplayName("Should contain OWNER and ADMIN")
    void containsAllValues() {
        assertThat(LockType.values()).containsExactly(
                LockType.OWNER,
                LockType.ADMIN
        );
    }

    @Test
    @DisplayName("valueOf should work for OWNER")
    void valueOfOwner() {
        assertThat(LockType.valueOf("OWNER")).isEqualTo(LockType.OWNER);
    }

    @Test
    @DisplayName("valueOf should work for ADMIN")
    void valueOfAdmin() {
        assertThat(LockType.valueOf("ADMIN")).isEqualTo(LockType.ADMIN);
    }

    @Test
    @DisplayName("valueOf should throw for invalid value")
    void valueOfInvalid() {
        assertThatThrownBy(() -> LockType.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

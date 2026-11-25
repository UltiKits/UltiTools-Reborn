package com.ultikits.ultitools.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleContentKindTest {

    @Test
    void initialKindsMatchThePattern() {
        assertThat(ModuleContentKind.ENCHANTMENT).isEqualTo("enchantment");
        assertThat(ModuleContentKind.DATAPACK).isEqualTo("datapack");
        assertThat(ModuleContentKind.TAG).isEqualTo("tag");
        assertThat(ModuleContentKind.UNKNOWN).isEqualTo("unknown");
        assertThat(ModuleContentKind.PATTERN.pattern()).isEqualTo("^[a-z][a-z0-9_]{0,31}$");
        assertThat(ModuleContentKind.ENCHANTMENT).matches(ModuleContentKind.PATTERN);
        assertThat(ModuleContentKind.DATAPACK).matches(ModuleContentKind.PATTERN);
        assertThat(ModuleContentKind.TAG).matches(ModuleContentKind.PATTERN);
        assertThat(ModuleContentKind.UNKNOWN).matches(ModuleContentKind.PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Enchantment", "1abc", "a-b", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsInvalidKinds(String kind) {
        assertThatThrownBy(() -> new ModuleContentKind(kind, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Content kind must match " + ModuleContentKind.PATTERN.pattern() + ": " + kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a0_b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "future_kind"})
    void acceptsOpenKindsWithinTheLengthLimit(String kind) {
        assertThat(new ModuleContentKind(kind, 1).kind()).isEqualTo(kind);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveCounts(int count) {
        assertThatThrownBy(() -> new ModuleContentKind(ModuleContentKind.TAG, count))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 100_000})
    void preservesValidCounts(Integer count) {
        assertThat(new ModuleContentKind(ModuleContentKind.TAG, count).count()).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(ints = {100_001, Integer.MAX_VALUE})
    void capsLargeCounts(int count) {
        assertThat(ModuleContentKind.MAX_COUNT).isEqualTo(100_000);
        assertThat(new ModuleContentKind(ModuleContentKind.TAG, count).count()).isEqualTo(100_000);
    }

    @Test
    void unknownAlwaysHasAnUnknownCount() {
        assertThat(new ModuleContentKind(ModuleContentKind.UNKNOWN, 5).count()).isNull();
    }

    @Test
    void rejectsNullKinds() {
        assertThatThrownBy(() -> new ModuleContentKind(null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}

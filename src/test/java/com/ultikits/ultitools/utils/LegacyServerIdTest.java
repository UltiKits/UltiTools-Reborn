package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;

/**
 * {@link LegacyServerId#canonical(String)} must equal the backend's {@code canonicalLegacyUuid}
 * (backend schema decision 22) case for case.
 * <p>
 * The cases are the backend's own golden table, the vendored contract 2.0.0
 * {@code fixtures/legacy-uuid-canonicalization.json} ({@code {rule, cases: [{name, input, expected}]}},
 * frozen and hash-checked by {@code ContractSyncTest}; plan 22-20, S-64).
 */
@DisplayName("LegacyServerId: the legacyServerUuid rule equals the backend's canonicalLegacyUuid")
class LegacyServerIdTest {

    static Stream<Arguments> cases() {
        JsonArray array = ContractFixtures.legacyUuidCases();
        List<Arguments> cases = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject row = element.getAsJsonObject();
            cases.add(Arguments.of(text(row, "name"), text(row, "input"), text(row, "expected")));
        }
        return cases.stream();
    }

    private static String text(JsonObject row, String member) {
        JsonElement value = row.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("each case of the backend rule")
    void canonicalMatchesTheBackendRule(String name, String input, String expected) {
        assertThat(LegacyServerId.canonical(input)).as(name).isEqualTo(expected);
    }

    @Test
    @DisplayName("the vendored table is complete (26 cases, null input included) and covers accepted and rejected inputs")
    void theTableIsComplete() {
        List<Arguments> all = new ArrayList<>();
        cases().forEach(all::add);
        assertThat(all).hasSize(26).anyMatch(a -> a.get()[1] == null)
                .anyMatch(a -> a.get()[2] == null).anyMatch(a -> a.get()[2] != null);
    }
}

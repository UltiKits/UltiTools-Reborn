package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
import com.google.gson.JsonParser;

/**
 * {@link LegacyServerId#canonical(String)} must equal the backend's {@code canonicalLegacyUuid}
 * (backend schema decision 22) case for case.
 * <p>
 * The cases are the backend's own golden table, {@link #FIXTURE}: a pre-freeze copy of contract
 * 2.0.0's {@code legacy-uuid-canonicalization.json} (provenance in that directory's
 * {@code PROVENANCE.md}), read as {@code {cases: [{name, input, expected}]}} or as a bare array of the
 * same objects. Plan 22-08 points {@link #FIXTURE} at the frozen, vendored copy.
 */
@DisplayName("LegacyServerId: the legacyServerUuid rule equals the backend's canonicalLegacyUuid")
class LegacyServerIdTest {

    /** The backend's case table; repointed at the frozen contract copy by plan 22-08. */
    static final String FIXTURE = "/ulticloud-contract-prerelease/legacy-uuid-canonicalization.json";

    static Stream<Arguments> cases() throws IOException {
        JsonElement root;
        try (InputStream in = LegacyServerIdTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture %s on the test classpath", FIXTURE).isNotNull();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader);
            }
        }
        JsonArray array = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("cases");
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
    @DisplayName("the backend table is complete (26 cases) and covers accepted and rejected inputs")
    void theTableIsNotEmpty() throws IOException {
        List<Arguments> all = new ArrayList<>();
        cases().forEach(all::add);
        assertThat(all).hasSize(26).anyMatch(a -> a.get()[2] == null).anyMatch(a -> a.get()[2] != null);
    }
}

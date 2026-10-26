package com.ultikits.ultitools.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The 6.3.1 deprecations of the protocol v1 cloud surface, read from the generated ledger
 * {@code compatibility/deprecations.json} (plan 22-25, D-29, S-04, S-68).
 * <p>
 * The ledger is what {@code DeprecationRegistryGenerator} writes during {@code mvn verify} from the
 * {@code @Deprecated} annotations and the {@code @removeIn} javadoc tags, so this test proves the
 * generated artefact rather than the source text.
 */
@DisplayName("v1 cloud deprecations in the generated ledger (6.3.1)")
class V1CloudDeprecationTest {

    private static final String SINCE = "6.3.1";
    private static final String REMOVE_IN = "6.4.0";

    private static Map<String, JsonObject> ledger;

    @BeforeAll
    static void readLedger() throws IOException {
        Path file = Paths.get(System.getProperty("basedir", "."), "compatibility", "deprecations.json");
        ledger = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                ledger.put(entry.get("key").getAsString(), entry);
            }
        }
    }

    @Test
    @DisplayName("UltiCloudRequests#post is announced for removal in 6.4.0 and names CloudPlayerLinks")
    void postIsAnnouncedForRemoval() {
        JsonObject entry = ledger.get("com.ultikits.ultitools.utils.UltiCloudRequests#post(java.lang.String,java.lang.String)");

        assertThat(entry).as("ledger entry for UltiCloudRequests#post").isNotNull();
        assertThat(entry.get("since").getAsString()).isEqualTo(SINCE);
        assertThat(entry.get("forRemoval").getAsBoolean()).isTrue();
        assertThat(entry.get("removeIn").getAsString()).isEqualTo(REMOVE_IN);
        assertThat(entry.get("status").getAsString()).isEqualTo("ANNOUNCED");
        assertThat(entry.get("replacement").getAsString()).contains("CloudPlayerLinks");
    }
}

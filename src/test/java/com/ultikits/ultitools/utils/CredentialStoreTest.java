package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link CredentialStore}'s contract: one owner of {@code data.json}, an atomic
 * temp-plus-rename replace, and a parse failure reported as an outcome distinct from absence
 * (plan 08-13, D-12/D-14).
 */
@DisplayName("CredentialStore -- single-owner, atomically-replaced data.json")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialStoreTest {

    @TempDir
    Path tempDir;

    private Path dataFile;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("data.json");
        CredentialStore.setTargetPathForTesting(dataFile);
    }

    @AfterEach
    void tearDown() {
        CredentialStore.clearTargetPathForTesting();
    }

    @Test
    @DisplayName("read() on an absent file reports absence and returns an empty map")
    void readOnAbsentFileReportsAbsentAndEmptyMap() {
        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isAbsent()).isTrue();
        assertThat(result.isParsed()).isFalse();
        assertThat(result.isParseFailure()).isFalse();
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("read() on a valid file returns its parsed contents")
    void readOnValidFileReturnsParsedContents() throws IOException {
        Files.write(dataFile, "{\"uuid\":\"abc123\",\"access_token\":\"tok\"}"
                .getBytes(StandardCharsets.UTF_8));

        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isParsed()).isTrue();
        assertThat(result.data())
                .containsEntry("uuid", "abc123")
                .containsEntry("access_token", "tok");
    }

    @Test
    @DisplayName("read() on a torn file reports a parse failure distinguishable from absence")
    void readOnTornFileReportsParseFailureDistinctFromAbsence() throws IOException {
        // A deliberately truncated fragment -- what a crash mid truncate-then-write leaves behind
        // under the two-writer design this class replaces.
        Files.write(dataFile, "{\"access_token\":\"partial-tok".getBytes(StandardCharsets.UTF_8));

        CredentialStore.ReadResult result = CredentialStore.read();

        assertThat(result.isParseFailure())
                .as("a torn file must not be reported as absent")
                .isTrue();
        assertThat(result.isAbsent()).isFalse();
        assertThat(result.isParsed()).isFalse();
        assertThatThrownBy(result::data)
                .as("a parse failure must not silently hand back an empty map as if the file were fresh")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("write() on an absent parent directory creates the directory and then the file")
    void writeOnAbsentParentDirectoryCreatesDirectoryThenFile() throws IOException {
        Path nestedFile = tempDir.resolve("nested").resolve("deeper").resolve("data.json");
        CredentialStore.setTargetPathForTesting(nestedFile);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("uuid", "created");
        CredentialStore.write(doc);

        assertThat(Files.exists(nestedFile)).isTrue();
        assertThat(new String(Files.readAllBytes(nestedFile), StandardCharsets.UTF_8)).contains("created");
    }

    @Test
    @DisplayName("write() replaces an existing file's contents completely, no residue of a longer document")
    void writeReplacesExistingContentCompletely() throws IOException {
        Map<String, Object> longDoc = new LinkedHashMap<>();
        longDoc.put("uuid", "a-long-uuid-value-that-will-not-survive-the-next-write");
        longDoc.put("access_token", "a-long-access-token-value-padding-padding-padding-padding");
        longDoc.put("refresh_token", "a-long-refresh-token-value-padding-padding-padding-padding");
        CredentialStore.write(longDoc);

        Map<String, Object> shortDoc = new LinkedHashMap<>();
        shortDoc.put("uuid", "short");
        CredentialStore.write(shortDoc);

        String content = new String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8);
        assertThat(content)
                .as("no residue of the longer previous document may survive a complete replace")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token")
                .doesNotContain("padding");

        CredentialStore.ReadResult reread = CredentialStore.read();
        assertThat(reread.isParsed()).isTrue();
        assertThat(reread.data()).hasSize(1).containsEntry("uuid", "short");
    }

    @Test
    @DisplayName("after write(), no temporary file remains in the parent directory")
    void writeLeavesNoTemporaryFileBehind() throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("uuid", "clean");
        CredentialStore.write(doc);

        try (Stream<Path> siblings = Files.list(tempDir)) {
            List<String> names = siblings.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertThat(names)
                    .as("the parent directory must contain exactly the target file, no leftover temp file")
                    .containsExactly("data.json");
        }
    }
}

package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exact owner-id persistence using synthetic documents only; no Bukkit server is needed. */
class ServerCredentialTest {

    private static final long MAX_USER_ID = 9007199254740991L;
    private static final String SECRET = "ucs_TESTSECRET";
    private static final long LINKED_AT = 1_700_000_000_000L;

    @TempDir
    Path temp;
    private Path credentialFile;

    @BeforeEach
    void setUp() {
        credentialFile = temp.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(credentialFile);
        CredentialStore.setOldLocationForTesting(temp.resolve("pre-migration-data.json"));
    }

    @AfterEach
    void tearDown() {
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
    }

    private static ServerCredential credential(Long userId) {
        return new ServerCredential(SECRET, "cred-1", "test-server", userId, "Survival", LINKED_AT);
    }

    private static Map<String, Object> document(Object userId) {
        Map<String, Object> entry = credential(43L).toDocument();
        entry.put(ServerCredential.FIELD_USER_ID, userId);
        return entry;
    }

    @Test
    void documentHasExactlySixKeysAndANumericOwnerId() {
        Map<String, Object> entry = credential(43L).toDocument();

        assertThat(entry).containsOnlyKeys("credential", "credentialId", "serverId", "userId", "serverName", "linkedAt")
                .containsEntry("userId", 43L);
        assertThat(entry.get("userId")).isInstanceOf(Long.class);
        assertThat(ServerCredential.fromDocument(entry).getUserId()).isEqualTo(43L);
    }

    static Stream<Arguments> validStoredOwnerIds() {
        return Stream.of(
                Arguments.of((byte) 1, 1L),
                Arguments.of((short) 43, 43L),
                Arguments.of(43, 43L),
                Arguments.of(43L, 43L),
                Arguments.of(MAX_USER_ID, MAX_USER_ID),
                Arguments.of(43.0, 43L),
                Arguments.of((double) MAX_USER_ID, MAX_USER_ID),
                Arguments.of(43.0f, 43L),
                Arguments.of(new BigInteger("9007199254740991"), MAX_USER_ID),
                Arguments.of(new BigDecimal("43.0"), 43L),
                Arguments.of(new BigDecimal("4.3e1"), 43L),
                Arguments.of("43", 43L),
                Arguments.of("00043", 43L),
                Arguments.of("9007199254740991", MAX_USER_ID));
    }

    @ParameterizedTest(name = "stored owner #{index} -> {1}")
    @MethodSource("validStoredOwnerIds")
    void exactNumbersAndLegacyDecimalStringsAreReadable(Object value, long expected) {
        ServerCredential loaded = ServerCredential.fromDocument(document(value));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo(expected);
        assertThat(loaded.toDocument()).containsEntry("userId", expected);
    }

    static Stream<Number> invalidStoredNumbers() {
        return Stream.of(0L, -1L, MAX_USER_ID + 1, Long.MAX_VALUE, Long.MIN_VALUE, 43.5,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                new BigDecimal("43.000000000000001"), new BigDecimal("9007199254740991.1"),
                new BigInteger("18446744073709551659"), new BigDecimal("1e10000"),
                new BigDecimal("-1e10000"), new BigDecimal("1e-10000"));
    }

    @ParameterizedTest(name = "invalid stored number #{index} never truncates or wraps")
    @MethodSource("invalidStoredNumbers")
    void invalidNumbersDoNotBecomeAnOwnerId(Number value) {
        ServerCredential loaded = ServerCredential.fromDocument(document(value));

        assertThat(loaded).as("invalid optional metadata does not force re-login").isNotNull();
        assertThat(loaded.getUserId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "u-1", " 43", "43 ", "+43", "-1", "0", "43.0", "4.3e1", "0x2b",
            "43.5", "9007199254740992", "18446744073709551659"})
    void legacyTextMustBeDigitOnlyAndInRange(String value) {
        assertThat(ServerCredential.fromDocument(document(value)).getUserId()).isNull();
    }

    @Test
    void nonNumericMetadataIsNotCoerced() {
        for (Object value : new Object[]{null, true, new LinkedHashMap<>(), new Object[]{43}}) {
            assertThat(ServerCredential.fromDocument(document(value)).getUserId()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 43L, MAX_USER_ID})
    void saveAndLoadKeepTheNumericOwnerAndExactlySixKeys(long userId) throws IOException {
        ServerCredentialStore.save(credential(userId));

        JsonObject entry = storedEntry();
        assertThat(entry.keySet()).containsExactlyInAnyOrder(
                "credential", "credentialId", "serverId", "userId", "serverName", "linkedAt");
        assertThat(entry.get("userId").getAsJsonPrimitive().isNumber()).isTrue();
        assertThat(entry.get("userId").getAsBigDecimal().longValueExact()).isEqualTo(userId);
        Object decoded = ((Map<?, ?>) CredentialStore.read().data().get(ServerCredentialStore.KEY)).get("userId");
        assertThat(decoded).as("the actual Gson Map reader returns Double").isInstanceOf(Double.class);
        assertThat(ServerCredentialStore.load().getUserId()).isEqualTo(userId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"43", "9007199254740991"})
    void legacyStoredDecimalTextLoadsWithoutRewritingTheFile(String userId) throws IOException {
        writeDocument(document(userId));
        byte[] before = Files.readAllBytes(credentialFile);

        assertThat(ServerCredentialStore.load().getUserId()).isEqualTo(Long.valueOf(userId));
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
    }

    @Test
    void missingOldMetadataStaysReadableAndDoesNotRewriteOrForceRelogin() throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("credential", SECRET);
        entry.put("serverId", "test-server");
        writeDocument(entry);
        byte[] before = Files.readAllBytes(credentialFile);

        ServerCredential loaded = ServerCredentialStore.load();

        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isNull();
        assertThat(loaded.getCredentialId()).isNull();
        assertThat(loaded.getServerName()).isNull();
        assertThat(loaded.getLinkedAt()).isZero();
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
    }

    @Test
    void rotationKeepsAllLinkMetadataInMemory() {
        ServerCredential rotated = credential(MAX_USER_ID).rotated("ucs_ROTATEDSECRET", "cred-2");

        assertThat(rotated.bearerValue()).isEqualTo("ucs_ROTATEDSECRET");
        assertThat(rotated.getCredentialId()).isEqualTo("cred-2");
        assertThat(rotated.getUserId()).isEqualTo(MAX_USER_ID);
        assertThat(rotated.getServerId()).isEqualTo("test-server");
        assertThat(rotated.getServerName()).isEqualTo("Survival");
        assertThat(rotated.getLinkedAt()).isEqualTo(LINKED_AT);
        assertThat(credential(null).rotated("ucs_ROTATEDSECRET", "cred-2").getUserId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"43", "\"43\"", "null", "missing"})
    void storedRotationReplacesOnlySecretAndIdWithoutMigratingMetadata(String json) throws IOException {
        Map<String, Object> entry = document(null);
        if ("missing".equals(json)) {
            entry.remove("userId");
        } else {
            entry.put("userId", new Gson().fromJson(json, Object.class));
        }
        entry.put("retainedMetadata", "unchanged");
        writeDocument(entry);
        JsonObject expected = storedEntry();
        expected.addProperty("credential", "ucs_ROTATEDSECRET");
        expected.addProperty("credentialId", "cred-2");

        assertThat(ServerCredentialStore.replace("test-server", "ucs_ROTATEDSECRET", "cred-2")).isTrue();
        assertThat(storedEntry()).isEqualTo(expected);
        assertThat(ServerCredentialStore.load().getUserId()).isEqualTo(
                "missing".equals(json) || "null".equals(json) ? null : 43L);
    }

    private void writeDocument(Map<String, Object> entry) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ServerCredentialStore.KEY, entry);
        Files.write(credentialFile, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject storedEntry() throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(credentialFile), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject(ServerCredentialStore.KEY);
    }
}

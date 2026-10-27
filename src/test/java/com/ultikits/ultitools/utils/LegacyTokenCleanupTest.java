package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.exceptions.DataAccessException;

class LegacyTokenCleanupTest {
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String LEGACY = "{\"uuid\":\"" + UUID + "\",\"cloud_token\":{\"access_token\":\"old-test-only\"}}";
    private static final String WARNING = "The account login saved by an earlier UltiTools version is no longer used and was removed from %s. Run /ulticloud login to re-attach this server.";
    @TempDir Path tempDir;
    private Path file;
    private Path oldFile;
    private HttpServer http;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestHelper.mockUltiToolsInstance(plugin -> {
            Logger logger = Logger.getLogger("LegacyTokenCleanupTest." + System.nanoTime());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) { records.add(record); }
                @Override public void flush() { }
                @Override public void close() { }
            });
            lenient().when(plugin.getLogger()).thenReturn(logger);
        });
        CloudSession.resetForTesting();
        file = tempDir.resolve("credentials.json");
        oldFile = tempDir.resolve("plugins/UltiTools/data.json");
        CredentialStore.setTargetPathForTesting(file);
        CredentialStore.setOldLocationForTesting(oldFile);
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        http.start();
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + http.getAddress().getPort());
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        assertThat(requests.get()).as("legacy token is never sent").isZero();
        assertThat(records).allSatisfy(record -> assertThat(record.getMessage()).doesNotContain("old-test-only"));
        CloudSession.resetForTesting();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        HttpRequestUtils.resetBaseUrl();
        http.stop(0);
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void removesOnlyObsoleteTokenAndWarnsOnceInSelectedLanguage(String language) throws Exception {
        com.google.gson.JsonObject catalogue = JsonParser.parseString(new String(Files.readAllBytes(
                java.nio.file.Paths.get("src/main/resources/lang/" + language + ".json")), StandardCharsets.UTF_8)).getAsJsonObject();
        lenient().when(UltiTools.getInstance().i18n(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return catalogue.has(key) ? catalogue.get(key).getAsString() : key;
        });
        Files.write(file, LEGACY.getBytes(StandardCharsets.UTF_8));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid").containsEntry("uuid", UUID);
        String expected = "zh".equals(language)
                ? "早期 UltiTools 版本保存的账号登录已不再使用，已从 %s 删除。请执行 /ulticloud login 接回此服务器。" : WARNING;
        assertThat(records.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage))
                .containsExactly(String.format(expected, file.toAbsolutePath()));
        byte[] before = hash();
        FileTime time = Files.getLastModifiedTime(file);
        records.clear();
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        assertThat(hash()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(time);
        assertThat(records).isEmpty();
    }

    @Test
    void migrationFromOldDataFileAlsoRemovesTheToken() throws Exception {
        Files.createDirectories(oldFile.getParent());
        Files.write(oldFile, LEGACY.getBytes(StandardCharsets.UTF_8));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        assertThat(oldFile).doesNotExist();
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid").containsEntry("uuid", UUID);
        assertThat(records.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage))
                .containsExactly(String.format(WARNING, file.toAbsolutePath()));
    }

    @Test
    void tokenBesideNewCredentialIsRemovedSilently() throws Exception {
        ServerCredentialStore.save(CloudLogoutRevokeTest.credential("NEW_TEST"));
        CredentialStore.update(document -> {
            document.put("uuid", UUID);
            document.put("cloud_token", java.util.Collections.singletonMap("access_token", "old-test-only"));
            return document;
        });
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid", "cloud_credential").containsEntry("uuid", UUID);
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW_TEST");
        assertThat(records).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tokenFreeDocumentKeepsHashAndModificationTime(boolean linked) throws Exception {
        Files.write(file, ("{  \"uuid\" : \"" + UUID + "\"  }\n").getBytes(StandardCharsets.UTF_8));
        if (linked) { ServerCredentialStore.save(CloudLogoutRevokeTest.credential("NEW_TEST")); }
        byte[] before = hash();
        FileTime pinned = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, pinned);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isEqualTo(linked);
        assertThat(hash()).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(pinned);
        assertThat(records).isEmpty();
    }

    @Test
    void missingFileRemainsMissing() {
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        assertThat(file).doesNotExist();
        assertThat(records).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"uuid\":", "null"})
    void unreadableDocumentKeepsItsBytesWithNoCleanupWarning(String text) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        byte[] before = hash();
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        assertThat(hash()).isEqualTo(before);
        assertThat(records.stream().filter(r -> r.getLevel() == Level.WARNING)).isEmpty();
    }

    @Test
    void updateFailureStopsStartupWithoutAnExtraCleanupWarning() throws Exception {
        Files.write(file, LEGACY.getBytes(StandardCharsets.UTF_8));
        CredentialStore.ReadResult parsed = CredentialStore.read();
        try (MockedStatic<CredentialStore> store = mockStatic(CredentialStore.class)) {
            store.when(CredentialStore::read).thenReturn(parsed);
            store.when(() -> CredentialStore.update(any())).thenThrow(new DataAccessException("synthetic failure"));
            assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
            store.verify(() -> CredentialStore.update(any()));
        }
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo(LEGACY);
        assertThat(records).isEmpty();
    }

    private byte[] hash() throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}

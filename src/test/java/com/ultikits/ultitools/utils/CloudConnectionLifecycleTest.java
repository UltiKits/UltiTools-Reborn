package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.plugin.PluginDescriptionFile;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.UltiTools;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CloudConnectionLifecycleTest {
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String REJECTED = "UltiCloud rejected this server's credential (it was revoked or is no longer valid). "
            + "The saved credential was deleted. Run /ulticloud login to link this server again.";

    @TempDir
    Path tempDir;
    private Path credentialFile;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private Gateway gateway;
    private Object previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = UltiTools.getInstance();
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        PluginInitiationUtils.startAcceptingPanelMessages();
        credentialFile = tempDir.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(credentialFile);
        CredentialStore.setOldLocationForTesting(tempDir.resolve("absent-old.json"));
        Files.write(credentialFile, ("{\"uuid\":\"" + UUID + "\"}").getBytes(StandardCharsets.UTF_8));
        Logger logger = Logger.getLogger("CloudConnectionLifecycleTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
        });
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        if (gateway != null) {
            gateway.stop(500);
        }
        CloudSession.resetForTesting();
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        ApiRateLimiter.resetAll();
        Field field = UltiTools.class.getDeclaredField("ultiTools");
        field.setAccessible(true);
        field.set(null, previousPlugin);
    }

    @Test
    void storedCredentialConnectsAndRevocationDeletesOnlyCloudEntryWithoutRetry() throws Exception {
        openGateway();
        ServerCredentialStore.save(credential("LIFE"));
        byte[] before = Files.readAllBytes(credentialFile);

        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        assertThat(gateway.headers).isEmpty();
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
        PluginInitiationUtils.initWebsocket();
        await(() -> gateway.peer != null, "startup connection");
        assertThat(gateway.paths).containsExactly("/v1/agent");
        assertThat(gateway.headers).containsExactly("Bearer ucs_LIFE");
        gateway.peer.close(4401, "revoked");

        await(() -> warningCount(REJECTED) == 1, "credential rejection notice");
        assertThat(CredentialStore.read().data()).containsEntry("uuid", UUID).doesNotContainKey("cloud_credential");
        assertThat(CloudSession.current().hasCredential()).isFalse();
        Thread.sleep(3100);
        assertThat(gateway.headers).hasSize(1);
        assertThat(warningCount(REJECTED)).isEqualTo(1);
        assertNoSecrets();
    }

    @Test
    void rejectionOfOldCredentialDoesNotDeleteAReplacementOnDisk() throws Exception {
        openGateway();
        ServerCredentialStore.save(credential("LIFE"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> gateway.peer != null, "startup connection");
        ServerCredentialStore.save(credential("NEW"));
        gateway.peer.close(4401, "revoked");

        await(() -> !CloudSession.current().hasCredential(), "old credential detached");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
        assertThat(CredentialStore.read().data()).containsEntry("uuid", UUID);
        assertNoSecrets();
    }

    private static ServerCredential credential(String suffix) {
        return new ServerCredential("ucs_" + suffix, "cred-" + suffix,
                "01JTESTSERVER00000000000000", "test-user", "Survival", 1L);
    }

    private void openGateway() throws Exception {
        gateway = new Gateway();
        gateway.start();
        assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + gateway.getPort());
    }

    private long warningCount(String message) {
        return records.stream().filter(r -> Level.WARNING.equals(r.getLevel()) && message.equals(r.getMessage())).count();
    }

    private void assertNoSecrets() {
        assertThat(records).allSatisfy(r -> assertThat(r.getMessage()).doesNotContain("ucs_LIFE", "ucs_NEW"));
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        AgentConnectionLoopbackTest.await(condition, description);
    }

    private static final class Gateway extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> headers = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private volatile WebSocket peer;

        Gateway() {
            super(new InetSocketAddress("127.0.0.1", 0), 1);
        }

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            headers.add(handshake.getFieldValue("Authorization"));
            paths.add(handshake.getResourceDescriptor());
            peer = conn;
        }
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { }
        @Override public void onMessage(WebSocket conn, String message) {
            if ("ping".equals(message)) { conn.send("pong"); }
        }
        @Override public void onError(WebSocket conn, Exception error) { }
        @Override public void onStart() { started.countDown(); }
    }
}

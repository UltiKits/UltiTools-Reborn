package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.commands.CloudLoginCommand;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CloudLogoutRevokeTest {
    static final String UUID = "0123456789abcdef0123456789abcdef";
    static final String SECRET = "ucs_LOGOUT_TEST";
    @TempDir Path tempDir;
    Path credentialFile;
    ServerMock server;
    HttpServer http;
    Gateway gateway;
    final List<String> lines = new CopyOnWriteArrayList<>();
    final List<LogRecord> records = new CopyOnWriteArrayList<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final CountDownLatch requestArrived = new CountDownLatch(1);
    final CountDownLatch replyAllowed = new CountDownLatch(1);
    volatile String authorization;
    volatile String contentType;
    volatile int bodyBytes;
    volatile boolean credentialPresentAtRevoke;
    volatile int replyStatus = 200;
    volatile boolean pauseReply;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        credentialFile = tempDir.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(credentialFile);
        CredentialStore.setOldLocationForTesting(tempDir.resolve("absent-old.json"));
        Files.write(credentialFile, ("{\"uuid\":\"" + UUID + "\"}").getBytes(StandardCharsets.UTF_8));
        Logger logger = Logger.getLogger("CloudLogoutRevokeTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/agent/credential/revoke", this::answer);
        http.start();
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + http.getAddress().getPort());
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        replyAllowed.countDown();
        server.getScheduler().waitAsyncTasksFinished();
        CloudSession.current().invalidate();
        if (gateway != null) { gateway.stop(500); }
        http.stop(0);
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void logoutClosesThenRevokesThenDeletesAndNeverReconnects() throws Exception {
        gateway = new Gateway();
        gateway.start();
        assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + gateway.getPort() + "/v1/agent"));
        ServerCredentialStore.save(credential("LOGOUT_TEST"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        AgentConnectionLoopbackTest.await(() -> CloudAuthManager.status().isConnected(), "linked connection");
        assertThat(records.stream().map(LogRecord::getMessage)).anyMatch(s -> s.startsWith("UltiCloud: connecting to"));
        int logoutLogIndex = records.size();
        pauseReply = true;

        new CloudLoginCommand().logout(sender());
        assertThat(lines).containsExactly(ChatColor.AQUA + "Unlinking this server from UltiCloud...");
        assertThat(CloudSession.current().isCurrent()).isFalse();
        // The gateway observes the socket close on its own thread, while logout() only *schedules*
        // the revoke on a tick; plan 22-07 also deletes the buffered state file before the teardown
        // (S-32), which widens that window without changing the product order. Awaiting the close
        // keeps the assertion below pinning that order -- close, then revoke -- rather than which
        // thread won a millisecond race.
        AgentConnectionLoopbackTest.await(() -> events.contains("closed"), "gateway saw the close");
        server.getScheduler().performOneTick();
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).as("revoke POST arrived").isTrue();
        assertThat(events).containsExactly("closed", "revoke");
        assertThat(authorization).isEqualTo("Bearer " + SECRET);
        assertThat(bodyBytes).isZero();
        assertThat(contentType).isNull();
        assertThat(credentialPresentAtRevoke).isTrue();
        assertThat(ServerCredentialStore.load()).isNotNull();
        replyAllowed.countDown();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(CredentialStore.read().data()).containsEntry("uuid", UUID).doesNotContainKey("cloud_credential");
        assertThat(CloudSession.current().getCredential()).isNull();
        assertThat(CloudAuthManager.status().isLinked()).isFalse();
        assertThat(lines).containsExactly(ChatColor.AQUA + "Unlinking this server from UltiCloud...",
                ChatColor.GREEN + "Unlinked this server from UltiCloud and revoked its credential. Cloud features are now stopped.",
                ChatColor.GRAY + "Use /ulticloud login to link it again.");
        Thread.sleep(3100);
        assertThat(gateway.opens.get()).isEqualTo(1);
        assertThat(records.subList(logoutLogIndex, records.size()).stream().map(LogRecord::getMessage))
                .noneMatch(s -> s.contains("UltiCloud: connecting to") || s.contains("retrying in"));
        assertThat(records).allSatisfy(r -> assertThat(r.getMessage()).doesNotContain(SECRET));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {200, 401, 500, 429, 302})
    void revokeOutcomesDeleteBothCloudEntries(int status) throws Exception {
        replyStatus = status;
        ServerCredentialStore.save(credential("LOGOUT_TEST"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        CredentialStore.update(document -> {
            document.put("cloud_token", java.util.Collections.singletonMap("access_token", "legacy-test-only"));
            return document;
        });
        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        server.getScheduler().performOneTick();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(outcomes).containsExactly(status == 200 || status == 401 ? CloudAuthManager.LogoutOutcome.REVOKED
                : CloudAuthManager.LogoutOutcome.DELETED_LOCALLY_REVOKE_FAILED);
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid");
        assertThat(CloudSession.current().getCredential()).isNull();
        assertThat(events).containsExactly("revoke");
        assertThat(authorization).isEqualTo("Bearer " + SECRET);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unavailableOrUnconfiguredEndpointStillDeletesLocally(boolean unconfigured) throws Exception {
        ServerCredentialStore.save(credential("LOGOUT_TEST"));
        if (unconfigured) {
            HttpRequestUtils.setBaseUrlForTesting("");
        } else {
            http.stop(0);
        }
        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        server.getScheduler().performOneTick();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(outcomes).containsExactly(CloudAuthManager.LogoutOutcome.DELETED_LOCALLY_REVOKE_FAILED);
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid");
    }

    @Test
    void pendingLogoutCancelsWithoutNetworkOrAnyFileWrite() throws Exception {
        Files.delete(credentialFile);
        DeviceLoginTestSupport.FakeClock clock = new DeviceLoginTestSupport.FakeClock();
        DeviceLoginTestSupport.FakeScheduler scheduler = new DeviceLoginTestSupport.FakeScheduler(clock);
        CloudSession.setDeviceSeamsForTesting(null, scheduler, clock);
        CloudSession.current().startDevicePolling(DeviceLoginTestSupport.grant(600, 5),
                new DeviceLoginTestSupport.RecordingListener());
        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        CloudAuthManager.logoutReporting(outcomes::add);
        assertThat(outcomes).containsExactly(CloudAuthManager.LogoutOutcome.CANCELLED_PENDING,
                CloudAuthManager.LogoutOutcome.NOT_LINKED);
        assertThat(CloudSession.current().isCurrent()).isFalse();
        assertThat(credentialFile).doesNotExist();
        assertThat(events).isEmpty();
    }

    @Test
    void lateLogoutCannotDeleteTheNewSessionsCredential() throws Exception {
        ServerCredentialStore.save(credential("LOGOUT_TEST"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        CloudSession old = CloudSession.current();
        pauseReply = true;
        CloudAuthManager.logoutReporting(outcome -> { });
        server.getScheduler().performOneTick();
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).isTrue();
        CloudSession fresh = CloudSession.startNew();
        assertThat(fresh.commitCredential(credential("NEW_TEST"))).isTrue();
        replyAllowed.countDown();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW_TEST");
        assertThat(fresh.getCredential().getCredentialId()).isEqualTo("cred-NEW_TEST");
        assertThat(old.getCredential()).isNull();
    }

    ConsoleCommandSender sender() {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
        return sender;
    }

    static ServerCredential credential(String suffix) {
        return new ServerCredential("ucs_" + suffix, "cred-" + suffix,
                "01JTESTSERVER00000000000000", "test-user", "Survival", 1L);
    }

    private void answer(HttpExchange exchange) throws IOException {
        try {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            while (exchange.getRequestBody().read() != -1) { bodyBytes++; }
            credentialPresentAtRevoke = CredentialStore.read().data().containsKey("cloud_credential");
            events.add("revoke");
            requestArrived.countDown();
            if (pauseReply && !replyAllowed.await(5, TimeUnit.SECONDS)) {
                throw new IOException("test reply timed out");
            }
            if (replyStatus == 302) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + http.getAddress().getPort() + "/redirect");
            }
            byte[] body = (replyStatus == 401 ? "{\"error\":{\"code\":\"invalid_credential\"}}"
                    : "{\"data\":{\"revoked\":true}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(replyStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        } finally {
            exchange.close();
        }
    }

    private final class Gateway extends WebSocketServer {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger opens = new AtomicInteger();
        Gateway() { super(new InetSocketAddress("127.0.0.1", 0), 1); }
        @Override public void onOpen(WebSocket peer, ClientHandshake handshake) { opens.incrementAndGet(); }
        @Override public void onClose(WebSocket peer, int code, String reason, boolean remote) { events.add("closed"); }
        @Override public void onMessage(WebSocket peer, String text) { if ("ping".equals(text)) { peer.send("pong"); } }
        @Override public void onError(WebSocket peer, Exception error) { }
        @Override public void onStart() { started.countDown(); }
    }
}

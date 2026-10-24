package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

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

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult.Kind;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CloudReconnectTest {
    @TempDir Path tempDir;
    private Path credentialFile;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private AgentConnectionLoopbackTest.RawGateway raw;
    private Gateway gateway;
    private Object previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = UltiTools.getInstance();
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        credentialFile = tempDir.resolve("credentials.json");
        CredentialStore.setTargetPathForTesting(credentialFile);
        CredentialStore.setOldLocationForTesting(tempDir.resolve("absent-old.json"));
        Logger logger = Logger.getLogger("CloudReconnectTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        if (gateway != null) { gateway.stop(500); }
        if (raw != null) { raw.close(); }
        CloudSession.resetForTesting();
        ApiRateLimiter.resetAll();
        HttpRequestUtils.resetBaseUrl();
        CredentialStore.clearTargetPathForTesting();
        CredentialStore.clearOldLocationForTesting();
        Field field = UltiTools.class.getDeclaredField("ultiTools");
        field.setAccessible(true);
        field.set(null, previousPlugin);
    }

    @Test
    void planLimitRetriesImmediatelyWithoutDeletingCredentialOrRepeatingWarning() throws Exception {
        raw = new AgentConnectionLoopbackTest.RawGateway(request -> AgentConnectionLoopbackTest.Reply.of(
                "402 Payment Required", "", AgentConnectionLoopbackTest.BODY_402));
        CloudSession.setAgentEndpointOverrideForTesting(URI.create("ws://127.0.0.1:" + raw.port() + "/v1/agent"));
        longRetry();
        adoptCredential();
        byte[] before = Files.readAllBytes(credentialFile);
        PluginInitiationUtils.initWebsocket();
        AgentConnectionLoopbackTest.await(() -> CloudSession.current().connectionState() == AgentConnection.State.PLAN_LIMITED
                && planWarnings() == 1, "plan-limit pause");
        assertThat(raw.requests).hasSize(1);

        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        AgentConnectionLoopbackTest.await(() -> raw.requests.size() == 2
                && CloudSession.current().connectionState() == AgentConnection.State.PLAN_LIMITED, "immediate retry");
        assertThat(planWarnings()).isEqualTo(1);
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
        ReconnectResult limited = CloudAuthManager.reconnect();
        assertThat(limited.getKind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(limited.getRemainingSeconds()).isBetween(1L, 10L);
        Thread.sleep(150);
        assertThat(raw.requests).hasSize(2);
    }

    @Test
    void backoffRetriesBeforeTheScheduledDelayOnRealSockets() throws Exception {
        gateway = new Gateway();
        gateway.start();
        assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();
        CloudSession.setAgentEndpointOverrideForTesting(URI.create("ws://127.0.0.1:" + gateway.getPort() + "/v1/agent"));
        longRetry();
        adoptCredential();
        PluginInitiationUtils.initWebsocket();
        AgentConnectionLoopbackTest.await(() -> CloudSession.current().connectionState() == AgentConnection.State.BACKOFF,
                "backoff after 1011");
        assertThat(gateway.opens.get()).isEqualTo(1);

        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        AgentConnectionLoopbackTest.await(() -> gateway.opens.get() == 2 && CloudAuthManager.status().isConnected(),
                "immediate second connection");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-RECONNECT_TEST");
    }

    @Test
    void unlinkedSessionDoesNotConnectOrCreateAFileAndStillUsesTheCooldown() {
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.NOT_LINKED);
        assertThat(credentialFile).doesNotExist();
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(records).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-started", "idle", "stopped"})
    void adoptedCredentialStartsOneFreshConnectionOnTheAgentScheduler(String initial) throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = runtime();
        adoptCredential();
        if (!"not-started".equals(initial)) {
            PluginInitiationUtils.initWebsocket();
            if ("stopped".equals(initial)) {
                runtime.runDue();
                runtime.last().accept();
                CloudSession.current().stopConnection();
            }
        }
        int before = runtime.attempts.size();
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        assertThat(runtime.attempts).hasSize(before);
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(before + 1);
        assertThat(runtime.last().headers.get("Authorization")).isEqualTo("Bearer ucs_RECONNECT_TEST");
        assertThat(ServerCredentialStore.load()).isNotNull();
    }

    @Test
    void connectedAndConnectingRefuseWithoutOpeningAnotherSocket() throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = connectedRuntime(false);
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.ALREADY_CONNECTING);
        runtime.last().accept();
        ApiRateLimiter.reset("reconnect");
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.ALREADY_CONNECTED);
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 426})
    void terminalRefusalIsNotRetried(int status) throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = connectedRuntime(false);
        runtime.last().refuse(status);
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(status == 401 ? Kind.NOT_LINKED : Kind.UPGRADE_REQUIRED);
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(1);
        assertThat(ServerCredentialStore.load() != null).isEqualTo(status == 426);
    }

    @Test
    void supersededSessionRetriesOnSchedulerWithItsCounterReset() throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = connectedRuntime(true);
        for (int i = 0; i < 3; i++) {
            runtime.last().close(4000);
            if (i < 2) {
                runtime.advance(60000L);
                runtime.last().accept();
            }
        }
        assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.SUPERSEDED);
        assertThat(runtime.attempts).hasSize(3);
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        assertThat(runtime.attempts).hasSize(3);
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(4);
        runtime.last().accept();
        runtime.last().close(4000);
        assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.BACKOFF);
        assertThat(ServerCredentialStore.load()).isNotNull();
    }

    @Test
    void backoffRetryIsQueuedAndLogoutBeforeExecutionDoesNotReviveIt() throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = connectedRuntime(true);
        runtime.last().close(1011);
        assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.BACKOFF);
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        assertThat(runtime.attempts).hasSize(1);
        CloudSession.current().invalidate();
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(1);
        ApiRateLimiter.reset("reconnect");
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.NOT_LINKED);
    }

    @Test
    void replacedSessionCannotRunItsQueuedRetry() throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = connectedRuntime(true);
        runtime.last().close(1011);
        assertThat(CloudAuthManager.reconnect().getKind()).isEqualTo(Kind.STARTED);
        CloudSession.startNew();
        runtime.runDue();
        assertThat(runtime.attempts).hasSize(1);
        assertThat(CloudSession.current().hasCredential()).isFalse();
    }

    private void adoptCredential() throws Exception {
        Files.write(credentialFile, "{\"uuid\":\"0123456789abcdef0123456789abcdef\"}".getBytes(StandardCharsets.UTF_8));
        ServerCredentialStore.save(CloudLogoutRevokeTest.credential("RECONNECT_TEST"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
    }

    private CloudReconnectStateMachineTest.AgentRuntime runtime() {
        CloudReconnectStateMachineTest.AgentRuntime runtime = new CloudReconnectStateMachineTest.AgentRuntime();
        runtime.install();
        return runtime;
    }

    private CloudReconnectStateMachineTest.AgentRuntime connectedRuntime(boolean open) throws Exception {
        CloudReconnectStateMachineTest.AgentRuntime runtime = runtime();
        adoptCredential();
        PluginInitiationUtils.initWebsocket();
        runtime.runDue();
        if (open) { runtime.last().accept(); }
        return runtime;
    }

    private long planWarnings() {
        return records.stream().filter(record -> record.getLevel() == Level.WARNING
                && record.getMessage().startsWith("UltiCloud has paused")).count();
    }

    private static void longRetry() {
        CloudSession.setAgentTimingOverrideForTesting(new ReconnectPolicy(() -> 0, 60000, 60000, 60000, 60000),
                AgentConnectionLoopbackTest.fastTiming(5000, 60000, 120000, 60000));
    }

    private static final class Gateway extends WebSocketServer {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger opens = new AtomicInteger();
        Gateway() { super(new InetSocketAddress("127.0.0.1", 0), 1); }
        @Override public void onOpen(WebSocket peer, ClientHandshake handshake) {
            if (opens.incrementAndGet() == 1) { peer.close(1011, "retry-test"); }
        }
        @Override public void onClose(WebSocket peer, int code, String reason, boolean remote) { }
        @Override public void onMessage(WebSocket peer, String text) { if ("ping".equals(text)) { peer.send("pong"); } }
        @Override public void onError(WebSocket peer, Exception error) { }
        @Override public void onStart() { started.countDown(); }
    }
}

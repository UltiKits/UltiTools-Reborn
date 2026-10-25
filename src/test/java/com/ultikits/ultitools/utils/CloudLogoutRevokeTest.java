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
import java.util.concurrent.ThreadPoolExecutor;
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
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

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
    volatile Boolean sessionCurrentAtRevoke;
    volatile Boolean connectedAtRevoke;
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
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
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
        // The order logout guarantees is local: the connection is stopped (session no longer
        // current, not connected) before the revoke is sent. It does not wait for the server-side
        // close handshake -- a revoke that reaches the backend first invalidates the credential and
        // the backend closes the socket itself, so the end state is the same. The revoke task does
        // not wait for a tick either: MockBukkit's runTaskAsynchronously hands it to its pool at
        // once, so the revoke POST and the gateway's onClose (on the gateway's own thread) can be
        // recorded in either order. The handler therefore records the client's state at the
        // instant the revoke arrives, and the gateway's close is awaited separately.
        assertThat(requestArrived.await(5, TimeUnit.SECONDS)).as("revoke POST arrived").isTrue();
        assertThat(sessionCurrentAtRevoke).as("session still current when the revoke arrived").isFalse();
        assertThat(connectedAtRevoke).as("still connected when the revoke arrived").isFalse();
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
        awaitWithin(15_000, () -> events.contains("closed"), "gateway saw the close");
        assertThat(new java.util.ArrayList<>(events)).containsExactlyInAnyOrder("closed", "revoke");
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
        awaitOutcome(outcomes);
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
        awaitOutcome(outcomes);
        assertThat(outcomes).containsExactly(CloudAuthManager.LogoutOutcome.DELETED_LOCALLY_REVOKE_FAILED);
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid");
    }

    /**
     * The revoke and the outcome callback run on a Bukkit asynchronous task. MockBukkit's
     * {@code waitAsyncTasksFinished()} counts only pool workers that hold a task
     * ({@code ThreadPoolExecutor#getActiveCount}), so a task that has been handed to the pool but not yet
     * taken by a worker -- for example one offered to an idle worker that has not locked it yet -- is
     * invisible to it, and the wait returns before the callback. This widens that hand-off on purpose: the
     * scheduler's wait returns with no outcome, and {@link #awaitOutcome(List)} still observes it.
     */
    @Test
    void logoutOutcomeIsAwaitedEvenWhenTheAsyncHandOffIsLate() throws Exception {
        ServerCredentialStore.save(credential("LOGOUT_TEST"));
        http.stop(0);
        CountDownLatch workerMayStart = new CountDownLatch(1);
        delayAsyncHandOff(workerMayStart);
        List<CloudAuthManager.LogoutOutcome> outcomes = new CopyOnWriteArrayList<>();
        CloudAuthManager.logoutReporting(outcomes::add);
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(outcomes).as("the scheduler's wait returned before the held revoke task ran").isEmpty();
        workerMayStart.countDown();
        awaitOutcome(outcomes);
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

    /**
     * Waits for the logout outcome itself. Do not replace this with {@code waitAsyncTasksFinished()}: that wait
     * can return before the revoke task has started (see
     * {@link #logoutOutcomeIsAwaitedEvenWhenTheAsyncHandOffIsLate()}).
     */
    static void awaitOutcome(List<CloudAuthManager.LogoutOutcome> outcomes) throws InterruptedException {
        awaitWithin(10_000, () -> !outcomes.isEmpty(), "the logout outcome");
    }

    /**
     * Replaces MockBukkit's async pool with one that hands each submitted task to its workers only after
     * {@code gate} opens (or after ten seconds), so no task is lost. Same pool shape as MockBukkit's.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void delayAsyncHandOff(CountDownLatch gate) throws ReflectiveOperationException {
        Field field = BukkitSchedulerMock.class.getDeclaredField("pool");
        field.setAccessible(true);
        field.set(server.getScheduler(), new HandOffDelayingPool(gate));
    }

    /** A cached pool whose hand-off of a submitted task to a worker waits for a gate. */
    static final class HandOffDelayingPool extends ThreadPoolExecutor {
        private final CountDownLatch gate;

        HandOffDelayingPool(CountDownLatch gate) {
            super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
            this.gate = gate;
        }

        @Override
        public void execute(Runnable task) {
            Thread handOff = new Thread(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                super.execute(task);
            }, "async-hand-off");
            handOff.setDaemon(true);
            handOff.start();
        }
    }

    /** Polls {@code condition} until it holds or {@code millis} pass; fails on the deadline. */
    static void awaitWithin(long millis, java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - end > 0) {
                throw new AssertionError("Timed out after " + millis + " ms waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    ConsoleCommandSender sender() {
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
        return sender;
    }

    static ServerCredential credential(String suffix) {
        return new ServerCredential("ucs_" + suffix, "cred-" + suffix,
                HandshakeEndToEndTest.SERVER_ID, "test-user", "Survival", 1L);
    }

    private void answer(HttpExchange exchange) throws IOException {
        try {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            while (exchange.getRequestBody().read() != -1) { bodyBytes++; }
            credentialPresentAtRevoke = CredentialStore.read().data().containsKey("cloud_credential");
            sessionCurrentAtRevoke = CloudSession.current().isCurrent();
            connectedAtRevoke = CloudAuthManager.status().isConnected();
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
        @Override public void onMessage(WebSocket peer, String text) {
            if ("ping".equals(text)) { peer.send("pong"); } else { HandshakeEndToEndTest.answerHello(peer, text); }
        }
        @Override public void onError(WebSocket peer, Exception error) { }
        @Override public void onStart() { started.countDown(); }
    }
}

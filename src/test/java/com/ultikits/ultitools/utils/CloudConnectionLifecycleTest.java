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
import java.util.concurrent.ScheduledExecutorService;
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
            + "The saved credential was deleted. If you used \"Revoke and re-pair\" in the panel, run "
            + "/ulticloud login <code> with the code it shows; otherwise run /ulticloud login to link this server again.";

    @TempDir
    Path tempDir;
    private Path credentialFile;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private Gateway gateway;
    private AgentConnectionLoopbackTest.RawGateway raw;
    private Object previousPlugin;
    /**
     * The agent threads this test's own sessions started, oldest first. A thread is never looked up by its name: the
     * name is JVM-wide, so an agent thread another test class left running (or one still winding down) would be taken
     * for this test's, and every join or state check on it would fail.
     */
    private final List<Thread> agentThreads = new CopyOnWriteArrayList<>();
    private final List<ControlledAgentExecutor> agentExecutors = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong agentTime = new java.util.concurrent.atomic.AtomicLong(1000L);

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = UltiTools.getInstance();
        CloudSession.resetForTesting();
        CloudSession.setAgentRuntimeForTesting(this::recordingAgentExecutor, null, agentTime::get);
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
        if (raw != null) {
            raw.close();
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
        await(() -> CloudAuthManager.status().isConnected(), "startup connection");
        assertThat(gateway.paths).containsExactly("/v1/agent");
        assertThat(gateway.headers).containsExactly("Bearer ucs_LIFE");
        gateway.peer.close(4401, "revoked");

        await(() -> warningCount(REJECTED) == 1, "credential rejection notice");
        assertThat(CredentialStore.read().data()).containsEntry("uuid", UUID).doesNotContainKey("cloud_credential");
        assertThat(CloudSession.current().hasCredential()).isFalse();
        advanceAgentTime(TimeUnit.HOURS.toMillis(1));
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
        await(() -> CloudAuthManager.status().isConnected(), "startup connection");
        ServerCredentialStore.save(credential("NEW"));
        gateway.peer.close(4401, "revoked");

        await(() -> !CloudSession.current().hasCredential(), "old credential detached");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
        assertThat(CredentialStore.read().data()).containsEntry("uuid", UUID);
        assertNoSecrets();
    }

    @Test
    void deviceGrantConnectsImmediatelyWithCommittedCredential() throws Exception {
        openGateway();
        DeviceLoginTestSupport.FakeClock clock = new DeviceLoginTestSupport.FakeClock();
        DeviceLoginTestSupport.FakeScheduler scheduler = new DeviceLoginTestSupport.FakeScheduler(clock);
        DeviceLoginTestSupport.ScriptedClient client = new DeviceLoginTestSupport.ScriptedClient(null)
                .thenPoll(DeviceAuthorizationClient.PollResult.granted(credential("NEW")));
        CloudSession.setDeviceSeamsForTesting(client, scheduler, clock);
        CloudSession.current().startDevicePolling(DeviceLoginTestSupport.grant(600, 1),
                new DeviceLoginTestSupport.RecordingListener());
        scheduler.runNext();
        await(() -> CloudAuthManager.status().isConnected(), "connection after grant");
        assertThat(gateway.headers).containsExactly("Bearer ucs_NEW");
        assertThat(ServerCredentialStore.load().getCredentialId()).isEqualTo("cred-NEW");
        await(() -> CloudAuthManager.status().isConnected(), "connected status");
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo("connected");
    }

    @Test
    void upgradeRefusalKeepsCredentialAndNamesMinimumOnce() throws Exception {
        raw = new AgentConnectionLoopbackTest.RawGateway(request -> AgentConnectionLoopbackTest.Reply.of(
                "426 Upgrade Required", "X-UltiCloud-Min-Framework: 6.3.2\r\n", null));
        startRaw();
        String warning = "UltiCloud refused this UltiTools version's protocol (HTTP 426). "
                + "Cloud features are stopped until UltiTools is upgraded; this server stays linked."
                + " UltiCloud requires UltiTools 6.3.2 or later.";
        await(() -> warningCount(warning) == 1, "upgrade notice");
        advanceAgentTime(300);
        assertThat(raw.requests).hasSize(1);
        assertThat(ServerCredentialStore.load()).isNotNull();
        assertThat(CloudAuthManager.status().isConnected()).isFalse();
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo(
                "stopped — this UltiTools version is no longer accepted; upgrade UltiTools");
    }

    @Test
    void serverIdMismatchStopsKeepsTheCredentialAndExplainsOnce() throws Exception {
        openGateway();
        fastTiming();
        gateway.welcomeServerId = "01JXXXXSERVER0000000000000";
        startSaved();
        byte[] before = Files.readAllBytes(credentialFile);
        await(() -> CloudSession.current().connectionState() == AgentConnection.State.SERVER_ID_MISMATCH,
                "server-ID mismatch stop");
        await(() -> records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE), "mismatch notice");
        advanceAgentTime(TimeUnit.HOURS.toMillis(1));
        assertThat(gateway.headers).hasSize(1);
        assertThat(Files.readAllBytes(credentialFile)).as("credential file unchanged").isEqualTo(before);
        assertThat(ServerCredentialStore.load()).isNotNull();
        assertThat(records.stream().filter(r -> r.getLevel() == Level.SEVERE)).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).isEqualTo(CloudSession.SERVER_ID_MISMATCH_KEY));
        assertThat(CloudAuthManager.status().isConnected()).isFalse();
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo(
                "stopped — UltiCloud answered with a different server ID than this server is linked to; "
                + "contact UltiCloud support, or run /ulticloud logout and then /ulticloud login");

        List<String> lines = new CopyOnWriteArrayList<>();
        org.bukkit.command.ConsoleCommandSender sender = org.mockito.Mockito.mock(org.bukkit.command.ConsoleCommandSender.class);
        org.mockito.Mockito.doAnswer(call -> { lines.add(call.getArgument(0)); return null; })
                .when(sender).sendMessage(org.mockito.ArgumentMatchers.anyString());
        com.ultikits.ultitools.commands.CloudLoginCommand command = new com.ultikits.ultitools.commands.CloudLoginCommand();
        command.status(sender);
        assertThat(lines).anyMatch(line -> line.contains("Connection: stopped — UltiCloud answered with a different server ID"));
        command.reconnect(sender);
        assertThat(lines.get(lines.size() - 1)).contains(
                "Reconnecting stays stopped: UltiCloud answered with a different server ID than this server is linked to.");
        advanceAgentTime(200);
        assertThat(gateway.headers).as("reconnect does not leave the mismatch").hasSize(1);
        assertNoSecrets();
    }

    @Test
    void serverIdMismatchLinesAreInBothCatalogues() throws Exception {
        for (String key : new String[]{CloudSession.SERVER_ID_MISMATCH_KEY,
                "Reconnecting stays stopped: UltiCloud answered with a different server ID than this server is linked to. "
                        + "Contact UltiCloud support, or run /ulticloud logout and then /ulticloud login."}) {
            com.google.gson.JsonObject en = catalogue("en");
            com.google.gson.JsonObject zh = catalogue("zh");
            assertThat(en.has(key)).as("en.json: " + key).isTrue();
            assertThat(en.get(key).getAsString()).isEqualTo(key);
            assertThat(zh.has(key)).as("zh.json: " + key).isTrue();
            assertThat(zh.get(key).getAsString()).isNotEqualTo(key).contains("/ulticloud login");
        }
    }

    private static com.google.gson.JsonObject catalogue(String language) throws java.io.IOException {
        return com.google.gson.JsonParser.parseString(new String(Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/resources/lang/" + language + ".json")), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void thirdSupersessionStopsWithoutDeletingCredential() throws Exception {
        openGateway();
        fastTiming();
        gateway.closeCode = 4000;
        startSaved();
        for (int attempt = 1; attempt < 3; attempt++) {
            final int completed = attempt;
            await(() -> records.stream().filter(r -> r.getMessage().startsWith(
                    "UltiCloud: another connection is using")).count() == completed, "supersession backoff");
            advanceAgentTime(100);
        }
        await(() -> records.stream().anyMatch(r -> r.getMessage().startsWith(
                "UltiCloud closed this server's connection three times")), "terminal supersession notice");
        assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.SUPERSEDED);
        advanceAgentTime(TimeUnit.HOURS.toMillis(1));
        assertThat(gateway.headers).hasSize(3);
        assertThat(records.stream().filter(r -> r.getMessage().startsWith(
                "UltiCloud closed this server's connection three times"))).hasSize(1);
        assertThat(ServerCredentialStore.load()).isNotNull();
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo(
                "stopped — another connection is using this server's credential");
    }

    @Test
    void planLimitWarnsOnceAcrossThreeQuietRetries() throws Exception {
        raw = new AgentConnectionLoopbackTest.RawGateway(request -> AgentConnectionLoopbackTest.Reply.of(
                "402 Payment Required", "", AgentConnectionLoopbackTest.BODY_402));
        startRaw();
        for (int attempt = 1; attempt <= 3; attempt++) {
            final int expected = attempt;
            await(() -> raw.requests.size() == expected, "plan-limit request " + attempt);
            await(() -> CloudSession.current().connectionState() == AgentConnection.State.PLAN_LIMITED, "plan-limit pause");
            // Drain the callback which publishes PLAN_LIMITED before advancing the retry clock.
            advanceAgentTime(0);
            if (attempt < 3) {
                advanceAgentTime(100);
            }
        }
        assertThat(raw.requests).hasSize(3);
        assertThat(records.stream().filter(r -> r.getLevel() == Level.WARNING
                && r.getMessage().startsWith("UltiCloud has paused"))).singleElement().satisfies(r ->
                assertThat(r.getMessage()).contains("the Pro plan expired and the account has more servers than its plan allows",
                        "Grace period ended at 2026-10-20T00:00:00.000Z.",
                        "Manage the plan: https://panel.ultikits.com/billing"));
        assertThat(records.stream().filter(r -> r.getLevel() == Level.INFO
                && r.getMessage().startsWith("UltiCloud: connecting to"))).hasSize(1);
        assertThat(ServerCredentialStore.load()).isNotNull();
        assertThat(CloudAuthManager.status().getConnectionDescription()).contains(
                "paused — plan limit reached; retrying every 15 minutes (next in 1 min)");
        assertNoSecrets();
    }

    @Test
    void disableStopsBackoffAndTerminatesAgentThread() throws Exception {
        assertStopsBackoff(PluginInitiationUtils::disableCloud);
    }

    @Test
    void stopWebsocketStopsBackoffAndTerminatesAgentThread() throws Exception {
        assertStopsBackoff(PluginInitiationUtils::stopWebsocket);
    }

    private void assertStopsBackoff(Runnable stop) throws Exception {
        openGateway();
        CloudSession.setAgentTimingOverrideForTesting(new ReconnectPolicy(() -> 0, 300, 300, 300, 300),
                AgentConnection.Timing.defaults());
        startSaved();
        await(() -> CloudAuthManager.status().isConnected(), "connected before teardown");
        Thread agent = agentThread();
        assertThat(agent).isNotNull();
        gateway.peer.close(1011, "retry");
        await(() -> CloudSession.current().connectionState() == AgentConnection.State.BACKOFF, "backoff");
        stop.run();
        // The double keeps delayed tasks across shutdown() exactly as production does, so the only
        // thing that can stop the pending retry is the product's own AgentConnection.stop().
        assertThat(lastExecutor().pendingDelayedAtShutdown())
                .as("teardown cancelled the retry before the agent executor stopped")
                .isZero();
        agent.join(2000);
        assertThat(agent.isAlive()).isFalse();
        advanceAgentTime(400);
        assertThat(gateway.headers).hasSize(1);
        assertThat(CloudSession.current().connectionState()).isEqualTo(AgentConnection.State.STOPPED);
    }

    @Test
    void reinitReplacesConnectionAndDoesNotStartASecondLoop() throws Exception {
        openGateway();
        startSaved();
        await(() -> CloudAuthManager.status().isConnected(), "initial connection");
        Thread first = agentThread();
        PluginInitiationUtils.reinitWebSocket();
        await(() -> gateway.headers.size() == 2, "fresh connection");
        await(() -> CloudAuthManager.status().isConnected(), "fresh welcome");
        first.join(2000);
        assertThat(first.isAlive()).isFalse();
        assertThat(agentExecutors.get(0).pendingDelayedAtShutdown())
                .as("the replaced connection left no retry behind")
                .isZero();
        advanceAgentTime(300);
        assertThat(gateway.headers).hasSize(2);
        assertThat(CloudAuthManager.status().isConnected()).isTrue();
    }

    @Test
    void invalidateNeverWaitsForCallbackParkedOnSessionMonitor() throws Exception {
        openGateway();
        startSaved();
        await(() -> CloudAuthManager.status().isConnected(), "connection before parked callback");
        CloudSession session = CloudSession.current();
        Thread agent = agentThread();
        byte[] before = Files.readAllBytes(credentialFile);
        synchronized (session) {
            gateway.peer.close(4401, "revoked");
            await(() -> agent.getState() == Thread.State.BLOCKED, "agent waiting on session monitor");
            // Returning while the callback is still blocked on this monitor proves no join;
            // the class timeout guards a deadlock, not a loaded host's sub-second speed.
            session.invalidate();
            assertThat(agent.getState()).isEqualTo(Thread.State.BLOCKED);
            // CloudSession's contract: teardown never waits for the agent thread. The state check
            // above only catches an unbounded wait (through the class timeout); a bounded
            // awaitTermination would pass it, so the executor double records every such call.
            assertThat(lastExecutor().awaitTerminationTimeouts())
                    .as("invalidate() never awaits the agent thread")
                    .isEmpty();
        }
        agent.join(2000);
        assertThat(agent.isAlive()).isFalse();
        assertThat(Files.readAllBytes(credentialFile)).isEqualTo(before);
        assertThat(warningCount(REJECTED)).isZero();
    }

    @Test
    void agentThreadIsTheOneThisSessionStartedEvenBesideAnotherWithTheSameName() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> decoys = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread decoy = new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "UltiTools-Cloud-Agent");
            decoy.setDaemon(true);
            decoy.start();
            decoys.add(decoy);
        }
        try {
            openGateway();
            startSaved();
            await(() -> CloudAuthManager.status().isConnected(), "connection beside the decoys");
            Thread agent = agentThread();
            assertThat(agent).isNotNull().isNotIn(decoys);
            CloudSession.current().invalidate();
            agent.join(2000);
            assertThat(agent.isAlive()).isFalse();
            assertThat(decoys).allMatch(Thread::isAlive);
        } finally {
            release.countDown();
        }
    }

    @Test
    void oldTokenNeverTriggersRefreshRegistrationOrLegacySocket() throws Exception {
        raw = new AgentConnectionLoopbackTest.RawGateway(request -> AgentConnectionLoopbackTest.reply401());
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + raw.port());
        CredentialStore.update(doc -> {
            doc.put("cloud_token", java.util.Collections.singletonMap("refresh_token", "old-test-refresh"));
            return doc;
        });
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isFalse();
        PluginInitiationUtils.initWebsocket();
        PluginInitiationUtils.stopCredentialSchedulers();
        // Nothing was resumed, so no agent connection exists and advancing the virtual agent clock
        // decides nothing here. Keep a short bounded real-time window instead: asynchronous work
        // started by initWebsocket() (a legacy socket handshake, a refresh POST for the old token)
        // must never reach the loopback server, and a single read could miss a late request.
        assertStaysEmpty(raw.requests, 300);
        assertThat(CredentialStore.read().data()).containsOnlyKeys("uuid").containsEntry("uuid", UUID);
        // Plan 22-24: the session has no user-token state any more; the old token adopted nothing.
        assertThat(CloudSession.current().hasCredential()).isFalse();
    }

    @Test
    void statusReportsConnectingThenConnectedOnlyAfterOpen() throws Exception {
        raw = new AgentConnectionLoopbackTest.RawGateway(request -> AgentConnectionLoopbackTest.Reply.silent());
        startRaw();
        await(() -> !raw.requests.isEmpty(), "unanswered upgrade");
        assertThat(CloudAuthManager.status().isConnected()).isFalse();
        assertThat(CloudAuthManager.status().getConnectionDescription()).isEqualTo("connecting");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void startupMessageUsesTheLinkSnapshotWhenItRuns(boolean stillLinked) throws Exception {
        ServerCredentialStore.save(credential("LIFE"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        UltiTools plugin = UltiTools.getInstance();
        org.bukkit.Server server = org.mockito.Mockito.mock(org.bukkit.Server.class);
        org.bukkit.scheduler.BukkitScheduler scheduler = org.mockito.Mockito.mock(org.bukkit.scheduler.BukkitScheduler.class);
        org.mockito.Mockito.when(server.getScheduler()).thenReturn(scheduler);
        com.ultikits.ultitools.interfaces.DataStore store =
                org.mockito.Mockito.mock(com.ultikits.ultitools.interfaces.DataStore.class);
        org.mockito.Mockito.when(store.getStoreType()).thenReturn("json");
        Field dataStore = UltiTools.class.getDeclaredField("dataStore");
        dataStore.setAccessible(true);
        dataStore.set(plugin, store);
        org.bukkit.configuration.file.YamlConfiguration env = new org.bukkit.configuration.file.YamlConfiguration();
        env.set("version", "6.3.1-SNAPSHOT");
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
             org.mockito.MockedStatic<UltiTools> tools = org.mockito.Mockito.mockStatic(UltiTools.class,
                     org.mockito.Mockito.CALLS_REAL_METHODS);
             org.mockito.MockedConstruction<com.ultikits.ultitools.manager.UpdateManager> updates =
                     org.mockito.Mockito.mockConstruction(com.ultikits.ultitools.manager.UpdateManager.class)) {
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(server);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(
                    org.mockito.Mockito.mock(org.bukkit.plugin.PluginManager.class));
            tools.when(UltiTools::getEnv).thenReturn(env);
            java.lang.reflect.Method startup = UltiTools.class.getDeclaredMethod("scheduleStartupMessages");
            startup.setAccessible(true);
            startup.invoke(plugin);
            org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            org.mockito.Mockito.verify(scheduler).scheduleSyncDelayedTask(org.mockito.Mockito.eq(plugin), task.capture());
            if (!stillLinked) {
                ServerCredentialStore.deleteIfMatches("cred-LIFE");
                assertThat(CloudSession.current().loadServerCredential()).isFalse();
            }
            task.getValue().run();
            assertThat(records.stream().map(LogRecord::getMessage)).contains(stillLinked
                    ? "UltiCloud: linked as Survival (server ID " + HandshakeEndToEndTest.SERVER_ID + "); connecting."
                    : "UltiCloud: Not linked. Use /ulticloud login to link this server.");
            assertThat(records.stream().map(LogRecord::getMessage)).noneMatch(line -> line.contains("linked as null"));
            assertThat(records.stream().map(LogRecord::getMessage)).anyMatch(line -> line.endsWith(UUID));
            assertThat(updates.constructed()).hasSize(1);
        }
    }

    private void startSaved() throws Exception {
        ServerCredentialStore.save(credential("LIFE"));
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
    }

    private void startRaw() throws Exception {
        HttpRequestUtils.setBaseUrlForTesting("http://127.0.0.1:" + raw.port());
        fastTiming();
        startSaved();
    }

    private static void fastTiming() {
        CloudSession.setAgentTimingOverrideForTesting(AgentConnectionLoopbackTest.fastPolicy(),
                AgentConnection.Timing.of(5000, 30000, 90000, 600000, 100));
    }

    /** @return the agent thread of the connection this test started last, or {@code null} before the first one */
    private Thread agentThread() {
        return agentThreads.isEmpty() ? null : agentThreads.get(agentThreads.size() - 1);
    }

    /** One real daemon agent thread, with retry/deadline time advanced only by the test. */
    private ScheduledExecutorService recordingAgentExecutor() {
        ControlledAgentExecutor executor = new ControlledAgentExecutor(agentTime, runnable -> {
            Thread thread = new Thread(runnable, "UltiTools-Cloud-Agent");
            thread.setDaemon(true);
            agentThreads.add(thread);
            return thread;
        });
        agentExecutors.add(executor);
        return executor;
    }

    private void advanceAgentTime(long millis) throws Exception {
        agentTime.addAndGet(millis);
        for (ControlledAgentExecutor executor : agentExecutors) {
            executor.runDue();
        }
    }

    /** The agent executor of the connection this test started last. */
    private ControlledAgentExecutor lastExecutor() {
        return agentExecutors.get(agentExecutors.size() - 1);
    }

    /**
     * Polls for {@code windowMillis} and fails if {@code requests} ever gains an entry: a bounded
     * real-time observation window for work that is asynchronous but has no timer to advance.
     */
    private static void assertStaysEmpty(List<?> requests, long windowMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMillis);
        while (System.nanoTime() < deadline) {
            assertThat(requests).as("no request during the observation window").isEmpty();
            Thread.sleep(10);
        }
        assertThat(requests).as("no request during the observation window").isEmpty();
    }

    private static ServerCredential credential(String suffix) {
        return new ServerCredential("ucs_" + suffix, "cred-" + suffix,
                HandshakeEndToEndTest.SERVER_ID, 43L, "Survival", 1L);
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
        private volatile int closeCode;
        private volatile String welcomeServerId = HandshakeEndToEndTest.SERVER_ID;

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
            if ("ping".equals(message)) {
                conn.send("pong");
            } else if (closeCode != 0) {
                // Wait for hello: closing inside onOpen races the client's own onOpened/send
                // callback and can manufacture an unrelated send-failure reconnect.
                conn.close(closeCode, "test close");
            } else {
                HandshakeEndToEndTest.answerHello(conn, message, welcomeServerId);
            }
        }
        @Override public void onError(WebSocket conn, Exception error) { }
        @Override public void onStart() { started.countDown(); }
    }
}

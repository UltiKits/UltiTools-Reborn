package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.OutboundTestAccess;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

/**
 * Plan 22-22 on the in-process stub (D-14, D-20, D-23, S-56, checker B-W3): the framework's producers reach the live
 * v2 connection. A console line and a sensitive command line leave only as redacted {@code log.lines}; the four
 * telemetry snapshots follow the welcome; nothing is sent with the logs switch off; lines logged while disconnected are
 * not buffered for the next connection. Every frame the stub records passed its frozen schema (the stub refuses and
 * logs {@code WS-INVALID} otherwise).
 */
@DisplayName("Outbound wiring on the live connection")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton
class OutboundWiringEndToEndTest {

    static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";
    private static final Logger CONSOLE = Logger.getLogger("Minecraft");

    @TempDir
    Path temp;

    private ServerMock server;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private final YamlConfiguration config = new YamlConfiguration();
    /** The server thread's queue: MockBukkit treats the test thread as the server thread, so it drains this. */
    private final BlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
    /** The repeating main-thread tasks the features started; the test runs them as the server would every second. */
    private final List<Runnable> mainTimers = new CopyOnWriteArrayList<>();
    private long lastTimerRun;
    /** What the framework logged through its own logger (PanelConnectionLog lines included). */
    private final List<LogRecord> pluginLog = new CopyOnWriteArrayList<>();
    private final List<RemoteActionLog.Entry> audit = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        Logger logger = Logger.getLogger("OutboundWiringEndToEndTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { pluginLog.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        config.set(Capability.POWER_ACTIONS.getConfigPath(), false);
        final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
        registry.register("login");
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getConfig()).thenReturn(config);
            lenient().when(plugin.getSensitiveCommandRegistry()).thenReturn(registry);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
        });
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        if (features != null) {
            features.shutdown();
        }
        stub.close();
        CloudTestAccess.reset();
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** The bindings every test starts from: a real monitor, the test's main-thread queue and timer. */
    CloudFeatures.Bindings bindings() {
        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forTesting();
        bindings.mainThread = mainQueue::add;
        bindings.disabling = () -> false;
        bindings.actionLog = audit::add;
        bindings.monitor = OutboundTestAccess::newMonitor;
        bindings.mainTimer = task -> {
            mainTimers.add(task);
            return () -> mainTimers.remove(task);
        };
        return bindings;
    }

    private void connect(CloudFeatures.Bindings bindings) throws Exception {
        features = CloudFeatures.start(bindings);
        connectStarted();
    }

    /** Connects with the features already started. */
    private void connectStarted() throws Exception {
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
    }

    /**
     * The log handler is attached when the welcome's listeners run, a moment after the status reports connected; a
     * probe line proves the stream is live before a test logs what it asserts on.
     */
    private void awaitStreaming() throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        int probe = 0;
        while (!logText().contains("stream probe")) {
            CONSOLE.info("stream probe " + probe++);
            await(() -> true, "nothing");
            Thread.sleep(200L);
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("the log stream never started");
            }
        }
    }

    @Test
    @DisplayName("tracer: a console line and a sensitive command line arrive as redacted log.lines")
    void consoleLinesReachTheGatewayRedacted() throws Exception {
        connect(bindings());
        awaitStreaming();
        CONSOLE.info("Console line one from the server");
        CONSOLE.info("Notch issued server command: /login secret");

        await(() -> logText().contains("Console line one from the server") && logText().contains("/login ***"),
                "both lines in log.lines");
        assertThat(allReceivedText()).doesNotContain("secret");
        assertThat(stub.gateway().sent("error")).isEmpty();
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("the welcome is followed by status and the players, plugins and worlds snapshots")
    void welcomeIsFollowedByTheFourSnapshots() throws Exception {
        connect(bindings());
        for (String type : new String[] {"status", "players.snapshot", "plugins.snapshot", "worlds.snapshot"}) {
            final String wanted = type;
            await(() -> !stub.gateway().received(wanted).isEmpty(), type);
        }
        JsonObject status = stub.gateway().received("status").get(0).getAsJsonObject("payload");
        assertThat(status.get("worldCount").getAsInt()).isEqualTo(1);
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("production order: the monitor is created after the features start, and telemetry still runs")
    void monitorCreatedAfterStartStillFeedsTelemetry() throws Exception {
        final AtomicReference<ServerMonitorManager> monitor = new AtomicReference<>();
        CloudFeatures.Bindings bindings = bindings();
        bindings.monitor = monitor::get;
        // UltiTools#onEnable starts the features before initWebSocketManagers() creates the monitor.
        features = CloudFeatures.start(bindings);
        monitor.set(OutboundTestAccess.newMonitor());
        connectStarted();

        for (String type : new String[] {"status", "players.snapshot", "plugins.snapshot", "worlds.snapshot"}) {
            final String wanted = type;
            await(() -> !stub.gateway().received(wanted).isEmpty(), type);
        }
        JsonObject hello = stub.gateway().received("hello").get(0).getAsJsonObject("payload");
        assertThat(hello.getAsJsonArray("capabilities").toString()).contains("\"telemetry\"");
        await(() -> !mainTimers.isEmpty(), "the 1 Hz main-thread sampler");
        String id = stub.gateway().send("snapshot.request", "all", null, null, null).get("id").getAsString();
        await(() -> !stub.gateway().received("snapshot.request.result").isEmpty(), "snapshot.request.result");
        JsonObject result = stub.gateway().received("snapshot.request.result").get(0);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(stub.gateway().sent("error")).isEmpty();
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("no monitor at all: the hello does not declare telemetry, a warning says why, and the router agrees")
    void noMonitorMeansNoTelemetryInTheHello() throws Exception {
        CloudFeatures.Bindings bindings = bindings();
        bindings.monitor = () -> null;
        connect(bindings);
        JsonObject hello = stub.gateway().received("hello").get(0).getAsJsonObject("payload");
        assertThat(hello.getAsJsonArray("capabilities").toString()).doesNotContain("\"telemetry\"")
                .contains("\"logs\"");
        assertThat(pluginLog).anySatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
            assertThat(record.getMessage()).contains("telemetry").contains("monitor");
        });

        int errorsBefore = stub.gateway().received("error").size();
        String id = stub.gateway().send("snapshot.request", "all", null, null, null).get("id").getAsString();
        await(() -> stub.gateway().received("error").size() > errorsBefore, "the refusal of snapshot.request");
        JsonObject refusal = stub.gateway().received("error").get(errorsBefore);
        assertThat(refusal.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(refusal.getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("capability_not_accepted");
        assertThat(stub.gateway().received("status")).isEmpty();
    }

    @Test
    @DisplayName("a v2-only session samples on the main thread: player snapshots change and the TPS varies")
    void v2OnlySessionSamplesTheLiveServer() throws Exception {
        connect(bindings());
        await(() -> !stub.gateway().received("players.snapshot").isEmpty(), "the first players.snapshot");
        await(() -> !mainTimers.isEmpty(), "the 1 Hz main-thread sampler");
        assertThat(mainTimers).as("one sampler for the plugin's lifetime").hasSize(1);

        server.addPlayer("alice");
        // The once-a-second main-thread run samples the new player; the changed snapshot leaves when the contract's
        // ten-second coalescing window of the first one has passed.
        await(() -> playerNames().contains("alice"), "a players snapshot naming alice");

        List<Double> tps = new ArrayList<>();
        runTimerOnce();
        Thread.sleep(400L);
        runTimerOnce();
        tps.add(OutboundTestAccess.currentTps());
        Thread.sleep(2600L);
        runTimerOnce();
        tps.add(OutboundTestAccess.currentTps());
        assertThat(tps.get(1)).as("TPS sampled in v2 only drops after a slow second").isLessThan(tps.get(0));
    }

    @Test
    @DisplayName("with the logs switch off nothing of log.lines is sent and no error frame appears")
    void logsSwitchOffSendsNothing() throws Exception {
        config.set(Capability.LOGS.getConfigPath(), false);
        connect(bindings());
        await(() -> !stub.gateway().received("status").isEmpty(), "status");
        CONSOLE.info("A line that must stay on the server");
        Thread.sleep(2500L);
        drainMain();
        assertThat(stub.gateway().received("log.lines")).isEmpty();
        assertThat(stub.gateway().sent("error")).isEmpty();
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("the logs switch turned off mid-session stops log.lines at once")
    void logsSwitchOffMidSession() throws Exception {
        connect(bindings());
        awaitStreaming();
        CONSOLE.info("Before the switch");
        await(() -> logText().contains("Before the switch"), "the first line");
        config.set(Capability.LOGS.getConfigPath(), false);
        CONSOLE.info("After the switch");
        Thread.sleep(2500L);
        assertThat(logText()).doesNotContain("After the switch");
        assertThat(stub.gateway().sent("error")).isEmpty();
    }

    @Test
    @DisplayName("after a close nothing is sent until the next welcome, and offline lines are not buffered")
    void offlineLinesAreNotBuffered() throws Exception {
        connect(bindings());
        awaitStreaming();
        CONSOLE.info("First connection line");
        await(() -> logText().contains("First connection line"), "the first line");

        stub.gateway().closeLive(1011, "test restart");
        await(() -> !CloudAuthManager.status().isConnected(), "disconnected");
        CONSOLE.info("Logged while offline");
        await(() -> CloudAuthManager.status().isConnected(), "reconnected");
        int before = stub.gateway().received("log.lines").size();
        int probe = 0;
        while (stub.gateway().received("log.lines").size() == before) {
            CONSOLE.info("second stream probe " + probe++);
            Thread.sleep(200L);
            await(() -> true, "nothing");
        }
        CONSOLE.info("Second connection line");
        await(() -> logText().contains("Second connection line"), "the second line");

        assertThat(logText()).doesNotContain("Logged while offline");
        // The second connection's first frame is its hello: nothing was attempted before the handshake.
        List<String> second = new ArrayList<>();
        int secondConnection = -1;
        for (com.ultikits.ultitools.stub.StubGateway.Frame frame : stub.gateway().frames()) {
            if (!"in".equals(frame.direction())) {
                continue;
            }
            if (secondConnection < 0 && "hello".equals(frame.type()) && !second.isEmpty()) {
                secondConnection = frame.connection();
            }
            second.add(frame.connection() + ":" + frame.type());
        }
        assertThat(secondConnection).as("a second hello").isPositive();
        String firstOfSecond = null;
        for (String entry : second) {
            if (entry.startsWith(secondConnection + ":")) {
                firstOfSecond = entry;
                break;
            }
        }
        assertThat(firstOfSecond).isEqualTo(secondConnection + ":hello");
        assertThat(stub.gateway().sent("error")).isEmpty();
    }

    // ------------------------------------------------------------------ guards across reconnects (22-22 fix)

    @Test
    @DisplayName("after a reconnect: still one main-thread sampler, one log handler, and each line sent once")
    void reconnectKeepsOneSamplerAndOneLogHandler() throws Exception {
        long handlersBefore = systemLogHandlers();
        connect(bindings());
        awaitStreaming();
        await(() -> !mainTimers.isEmpty(), "the 1 Hz main-thread sampler");

        reconnectAndAwaitStreaming();
        assertThat(mainTimers).as("one sampler for the plugin's lifetime, not one per connection").hasSize(1);
        assertThat(systemLogHandlers() - handlersBefore).as("one log handler on the root logger").isEqualTo(1);
        CONSOLE.info("Exactly once after the reconnect");
        await(() -> logText().contains("Exactly once after the reconnect"), "the line after the reconnect");
        Thread.sleep(1500L);
        assertThat(occurrences(logText(), "Exactly once after the reconnect")).isEqualTo(1);
    }

    @Test
    @DisplayName("a log teardown between connections (as a logout does) is repaired once at the next welcome")
    void aLogTeardownIsRepairedOnceAtTheNextWelcome() throws Exception {
        connect(bindings());
        awaitStreaming();
        // A logout's v1 log teardown (LogStreamManager#shutdown) removes every SystemLogHandler from the root logger.
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof com.ultikits.ultitools.handler.SystemLogHandler) {
                root.removeHandler(handler);
            }
        }
        reconnectAndAwaitStreaming();
        assertThat(systemLogHandlers()).as("re-attached exactly once").isEqualTo(1);
        CONSOLE.info("Once after the teardown");
        await(() -> logText().contains("Once after the teardown"), "the line after the teardown");
        Thread.sleep(1500L);
        assertThat(occurrences(logText(), "Once after the teardown")).isEqualTo(1);
    }

    @Test
    @DisplayName("a SEVERE record with an exception reaches log.lines with message and stack trace redacted")
    void anExceptionRecordIsRedactedInMessageAndStackTrace() throws Exception {
        connect(bindings());
        awaitStreaming();
        LogRecord record = new LogRecord(java.util.logging.Level.SEVERE,
                "first line\nNotch issued server command: /login secret3\nthird line");
        record.setLoggerName(CONSOLE.getName());
        record.setThrown(new IllegalStateException("outer /login secret1",
                new RuntimeException("Caused by typing /ultilogin:login secret2")));
        CONSOLE.log(record);

        await(() -> logText().contains("IllegalStateException") && logText().contains("third line"),
                "the exception record in log.lines");
        // The stack trace's first line carries the exception message; the line limit may cut the cause off.
        assertThat(logText()).contains("Notch issued server command: /login ***")
                .contains("java.lang.IllegalStateException: outer /login ***");
        assertThat(allReceivedText()).doesNotContain("secret");
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    private void reconnectAndAwaitStreaming() throws InterruptedException {
        stub.gateway().closeLive(1011, "test restart");
        await(() -> !CloudAuthManager.status().isConnected(), "disconnected");
        await(() -> CloudAuthManager.status().isConnected(), "reconnected");
        int before = stub.gateway().received("log.lines").size();
        int probe = 0;
        long end = System.currentTimeMillis() + 30_000L;
        while (stub.gateway().received("log.lines").size() == before) {
            CONSOLE.info("reconnect stream probe " + probe++);
            Thread.sleep(200L);
            await(() -> true, "nothing");
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("the log stream never restarted");
            }
        }
        // The welcome's listeners attach the handler first and start the sampler after it.
        Thread.sleep(500L);
    }

    private static long systemLogHandlers() {
        long count = 0;
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof com.ultikits.ultitools.handler.SystemLogHandler) {
                count++;
            }
        }
        return count;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ events (Task 2)

    private PlayerMock playerWithListener(String name) {
        server.getPluginManager().registerEvents(new CloudOutboxListener(), MockBukkit.createMockPlugin());
        return server.addPlayer(name);
    }

    @Test
    @DisplayName("a namespaced sensitive command reaches the gateway as player.command with its name only")
    void playerCommandCarriesOnlyTheName() throws Exception {
        connect(bindings());
        PlayerMock player = playerWithListener("Notch");
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, "/ultilogin:login secret"));

        await(() -> !stub.gateway().received("player.command").isEmpty(), "player.command");
        assertThat(stub.gateway().received("player.command")).hasSize(1);
        JsonObject payload = stub.gateway().received("player.command").get(0).getAsJsonObject("payload");
        assertThat(payload.toString()).isEqualTo("{\"name\":\"login\"}");
        assertThat(allReceivedText()).doesNotContain("secret");
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("a command another plugin cancelled is not reported as player.command")
    void cancelledCommandIsNotReported() throws Exception {
        connect(bindings());
        PlayerMock player = playerWithListener("Notch");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void block(PlayerCommandPreprocessEvent event) {
                if (event.getMessage().startsWith("/blocked")) {
                    event.setCancelled(true); // e.g. a login plugin before the player logged in
                }
            }
        }, MockBukkit.createMockPlugin());
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, "/blocked now"));
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, "/spawn"));

        await(() -> !stub.gateway().received("player.command").isEmpty(), "player.command");
        Thread.sleep(1000L);
        assertThat(stub.gateway().received("player.command")).hasSize(1);
        assertThat(stub.gateway().received("player.command").get(0).getAsJsonObject("payload").toString())
                .isEqualTo("{\"name\":\"spawn\"}");
    }

    @Test
    @SuppressWarnings("deprecation") // the String constructors are the ones every supported Paper keeps
    @DisplayName("a join and a leave enter the outbox as { playerUuid, playerName }, are sent and acked")
    void joinAndLeaveAreSentAndAcked() throws Exception {
        connect(bindings());
        PlayerMock player = playerWithListener("Steve");
        int joinsBefore = stub.gateway().received("player.join").size();
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, "joined"));
        server.getPluginManager().callEvent(new PlayerQuitEvent(player, "left"));

        await(() -> stub.gateway().received("player.join").size() > joinsBefore
                && !stub.gateway().received("player.leave").isEmpty(), "player.join and player.leave");
        JsonObject leave = stub.gateway().received("player.leave").get(0).getAsJsonObject("payload");
        assertThat(leave.keySet()).containsExactlyInAnyOrder("playerUuid", "playerName");
        assertThat(leave.get("playerName").getAsString()).isEqualTo("Steve");
        assertThat(leave.get("playerUuid").getAsString()).isEqualTo(player.getUniqueId().toString());
        await(() -> CloudSession.current().cloudOutbox().unacked().isEmpty(), "every event acked");
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
    }

    @Test
    @DisplayName("an error the collector caught arrives once as error.report with the padded group key, redacted")
    void errorReportReachesTheGateway() throws Exception {
        final ErrorReportCollector collector = new ErrorReportCollector();
        CloudFeatures.Bindings bindings = bindings();
        bindings.errors = () -> collector;
        connect(bindings);
        IllegalStateException error = new IllegalStateException("failed while running /login secret");
        collector.reportError(error, "UltiChat", TriggerContext.scheduled("tick"));

        await(() -> !stub.gateway().received("error.report").isEmpty(), "error.report");
        JsonObject payload = stub.gateway().received("error.report").get(0).getAsJsonObject("payload");
        String key = OutboundTestAccess.fingerprintOf(error);
        assertThat(Long.parseLong(payload.get("fingerprint").getAsString(), 16)).isEqualTo(Long.parseLong(key, 16));
        assertThat(payload.get("fingerprint").getAsString()).hasSizeGreaterThanOrEqualTo(8).endsWith(key);
        assertThat(payload.get("message").getAsString()).isEqualTo("failed while running /login ***");

        collector.reportError(error, "UltiChat", TriggerContext.scheduled("tick"));
        Thread.sleep(2500L);
        await(() -> true, "nothing");
        assertThat(stub.gateway().received("error.report")).as("counted, not resent").hasSize(1);
        assertThat(allReceivedText()).doesNotContain("secret");
        collector.shutdown();
    }

    @Test
    @DisplayName("snapshot.request is answered after the requested snapshots, with replyTo")
    void snapshotRequestIsAnsweredAfterTheSnapshots() throws Exception {
        connect(bindings());
        await(() -> !stub.gateway().received("worlds.snapshot").isEmpty(), "the opening snapshots");
        int statusBefore = stub.gateway().received("status").size();
        int worldsBefore = stub.gateway().received("worlds.snapshot").size();
        String id = stub.gateway().send("snapshot.request", "all", null, null, null).get("id").getAsString();

        await(() -> !stub.gateway().received("snapshot.request.result").isEmpty(), "snapshot.request.result");
        JsonObject result = stub.gateway().received("snapshot.request.result").get(0);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        JsonArray sent = result.getAsJsonObject("payload").getAsJsonObject("data").getAsJsonArray("sent");
        assertThat(sent.size()).isPositive();
        assertThat(stub.gateway().received("status").size()).isGreaterThan(statusBefore);
        assertThat(stub.gateway().received("worlds.snapshot").size()).isGreaterThan(worldsBefore);
        List<String> order = new ArrayList<>();
        for (com.ultikits.ultitools.stub.StubGateway.Frame frame : stub.gateway().frames()) {
            if ("in".equals(frame.direction())) {
                order.add(frame.type());
            }
        }
        assertThat(order.lastIndexOf("worlds.snapshot")).isLessThan(order.indexOf("snapshot.request.result"));
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getCapability()).isEqualTo("MONITORING");
            assertThat(entry.getAction()).isEqualTo("snapshot.request");
            assertThat(entry.getTarget()).isEqualTo("status,plugins,worlds");
            assertThat(entry.getActor()).isEqualTo("panel");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
            assertThat(entry.getReason()).isNull();
        });
    }

    @Test
    @DisplayName("a served single-target snapshot request records only the requested target")
    void singleTargetSnapshotRequestIsAudited() throws Exception {
        connect(bindings());
        await(() -> !stub.gateway().received("worlds.snapshot").isEmpty(), "the opening snapshots");
        assertThat(audit).as("unsolicited telemetry is not a panel read").isEmpty();
        stub.gateway().send("snapshot.request", "worlds", null, null, null);
        await(() -> !stub.gateway().received("snapshot.request.result").isEmpty(), "snapshot.request.result");

        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getAction()).isEqualTo("snapshot.request");
            assertThat(entry.getTarget()).isEqualTo("worlds");
            assertThat(entry.getActor()).isEqualTo("panel");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        });
    }

    @Test
    @DisplayName("a snapshot request while disabling is dropped without a reply or an action-log entry")
    void snapshotReadWhileDisablingIsNotAudited() throws Exception {
        AtomicBoolean disabling = new AtomicBoolean();
        AtomicBoolean handOffRefused = new AtomicBoolean();
        CloudFeatures.Bindings bindings = bindings();
        bindings.disabling = disabling::get;
        // Production's scheduleUnlessDisabling refuses the hand-off before any server-thread task is queued.
        bindings.mainThread = task -> {
            if (disabling.get()) {
                handOffRefused.set(true);
            } else {
                mainQueue.add(task);
            }
        };
        connect(bindings);
        await(() -> !stub.gateway().received("worlds.snapshot").isEmpty(), "the opening snapshots");
        drainMain();
        int worldsBefore = stub.gateway().received("worlds.snapshot").size();
        disabling.set(true);
        stub.gateway().send("snapshot.request", "worlds", null, null, null);
        await(handOffRefused::get, "the snapshot request's refused server-thread hand-off");

        assertThat(mainQueue).as("no snapshot task queued once disabling starts").isEmpty();
        assertThat(stub.gateway().received("snapshot.request.result")).as("the request is left unanswered").isEmpty();
        assertThat(stub.gateway().received("worlds.snapshot")).hasSize(worldsBefore);
        assertThat(audit).as("no read was served or audited once disabling starts").isEmpty();
    }

    @Test
    @DisplayName("a switched-off snapshot request keeps exactly one DENIED switch-gate entry")
    void refusedSnapshotRequestKeepsOneDeniedEntry() throws Exception {
        connect(bindings());
        await(() -> !stub.gateway().received("worlds.snapshot").isEmpty(), "the opening snapshots");
        config.set(Capability.MONITORING.getConfigPath(), false);
        stub.gateway().send("snapshot.request", "worlds", null, null, null);
        await(() -> !stub.gateway().received("error").isEmpty(), "snapshot refusal");

        JsonObject refusal = stub.gateway().received("error").get(0).getAsJsonObject("payload");
        assertThat(refusal.get("code").getAsString()).isEqualTo("capability_not_accepted");
        assertThat(stub.gateway().received("snapshot.request.result")).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getCapability()).isEqualTo("MONITORING");
            assertThat(entry.getAction()).isEqualTo("snapshot.request");
            assertThat(entry.getTarget()).isEqualTo("snapshot.request");
            assertThat(entry.getActor()).isEqualTo("panel");
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
            assertThat(entry.getReason()).isEqualTo(refusal.get("message").getAsString());
        });
    }

    @Test
    @SuppressWarnings("deprecation") // the String constructor is the one every supported Paper keeps
    @DisplayName("player events and error reporting turned off mid-session: no command, join or error frame leaves")
    void eventSwitchesOffSendNothing() throws Exception {
        final ErrorReportCollector collector = new ErrorReportCollector();
        CloudFeatures.Bindings bindings = bindings();
        bindings.errors = () -> collector;
        connect(bindings);
        PlayerMock player = playerWithListener("Alex");
        await(() -> !stub.gateway().received("player.join").isEmpty(), "the join of adding the player");
        int joins = stub.gateway().received("player.join").size();
        // Declared and accepted at the handshake; the operator switches them off afterwards (S-66).
        config.set(Capability.PLAYER_EVENTS.getConfigPath(), false);
        config.set("ultipanel.logging.error-reporting.enabled", false);
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, "/spawn"));
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, "joined"));
        collector.reportError(new IllegalStateException("off"), null, null);
        Thread.sleep(2500L);
        await(() -> true, "nothing");

        assertThat(stub.gateway().received("player.command")).isEmpty();
        assertThat(stub.gateway().received("player.join")).hasSize(joins);
        assertThat(stub.gateway().received("error.report")).isEmpty();
        assertThat(stub.gateway().sent("error")).isEmpty();
        collector.shutdown();
    }

    // ------------------------------------------------------------------ helpers

    private List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (JsonObject frame : stub.gateway().received("players.snapshot")) {
            for (JsonElement player : frame.getAsJsonObject("payload").getAsJsonArray("players")) {
                names.add(player.getAsJsonObject().get("name").getAsString());
            }
        }
        return names;
    }

    String logText() {
        StringBuilder text = new StringBuilder();
        for (JsonObject frame : stub.gateway().received("log.lines")) {
            for (JsonElement line : frame.getAsJsonObject("payload").getAsJsonArray("lines")) {
                text.append(line.getAsJsonObject().get("message").getAsString()).append('\n');
            }
        }
        return text.toString();
    }

    private String allReceivedText() {
        return stub.gateway().received(null).toString();
    }

    private void runTimerOnce() {
        drainMain();
        for (Runnable timer : mainTimers) {
            timer.run();
        }
        drainMain();
    }

    private void drainMain() {
        Runnable task;
        while ((task = mainQueue.poll()) != null) {
            task.run();
        }
    }

    void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        while (!condition.getAsBoolean()) {
            drainMain();
            long now = System.currentTimeMillis();
            if (now - lastTimerRun >= 1000L) {
                lastTimerRun = now;
                for (Runnable timer : mainTimers) {
                    timer.run();
                }
            }
            if (now > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}

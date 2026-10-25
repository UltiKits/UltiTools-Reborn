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
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.OutboundTestAccess;
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
            @Override public void publish(LogRecord record) { }
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
        bindings.monitor = OutboundTestAccess::newMonitor;
        bindings.mainTimer = task -> {
            mainTimers.add(task);
            return () -> mainTimers.remove(task);
        };
        return bindings;
    }

    private void connect(CloudFeatures.Bindings bindings) throws Exception {
        features = CloudFeatures.start(bindings);
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

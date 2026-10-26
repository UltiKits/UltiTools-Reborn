package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.OutboundTestAccess;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.stub.StubGateway;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Plan 22-23 Task 1, the v2-only guard (D-01, D-23, checker B-W6): a framework start with a stored server credential
 * and the build's API URL pointed at the in-process stub speaks only protocol v2. The start runs the cloud entry
 * points in {@code UltiTools#onEnable}'s order on the production bindings ({@link CloudFeatures.Bindings#forPlugin}),
 * with only the server-thread seams and the command capture replaced (MockBukkit treats the test thread as the
 * server thread, and Paper's capturing command sender does not exist in MockBukkit); the agent endpoint is derived
 * from the API URL exactly as in production, not overridden.
 * <p>
 * The session upgrades, says hello, receives its welcome, sends {@code status} and the snapshots, executes one
 * {@code command.execute} built from the frozen {@code command.execute/valid/say.json}, and streams one console line
 * as {@code log.lines}. Afterwards the stub's access log names no request outside the v2 paths the framework's own
 * clients use (read from their constants, not retyped) and no v1 path, and every frame either side sent is a type of
 * the frozen contract. This test is the regression fence of the v1 removal (22-23 and 22-24): it must stay green after
 * every deletion.
 */
@DisplayName("A framework start speaks only protocol v2")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton
class V2OnlySessionTest {

    private static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";
    private static final Logger CONSOLE = Logger.getLogger("Minecraft");
    /** {@code <instant> <CATEGORY> <METHOD> <target> ...} of the stub's request lines. */
    private static final Pattern REQUEST_LINE = Pattern.compile("^\\S+ (HTTP|UPGRADE|V1|TCP-RESET) (\\S+) (\\S+)");

    @TempDir
    Path temp;

    private ServerMock server;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private final YamlConfiguration config = new YamlConfiguration();
    /** The server thread's queue: MockBukkit treats the test thread as the server thread, so it drains this. */
    private final BlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
    /** The repeating server-thread tasks the features started; run every second as the server would. */
    private final List<Runnable> mainTimers = new CopyOnWriteArrayList<>();
    private long lastTimerRun;
    private PanelResponderRegistry responders;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        Path state = temp.resolve("server").resolve(".ultikits");
        Files.createDirectories(state);
        Logger logger = Logger.getLogger("V2OnlySessionTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { }
            @Override public void flush() { }
            @Override public void close() { }
        });
        config.set(Capability.COMMANDS.getConfigPath(), true);
        config.set(Capability.POWER_ACTIONS.getConfigPath(), false);
        responders = new PanelResponderRegistry();
        final CommandExecutionManager commands = mock(CommandExecutionManager.class);
        doAnswer(invocation -> {
            CommandExecutionManager.CommandResultSink sink = invocation.getArgument(3);
            sink.completed(Collections.singletonList("[Server] hello from the panel"), false, 2L);
            return null;
        }).when(commands).execute(anyString(), anyString(), any(), any());
        final RemoteActionLog actionLog = mock(RemoteActionLog.class);
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getConfig()).thenReturn(config);
            lenient().when(plugin.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
            lenient().when(plugin.getCommandExecutionManager()).thenReturn(commands);
            lenient().when(plugin.getRemoteActionLog()).thenReturn(actionLog);
            lenient().when(plugin.getPanelResponderRegistry()).thenReturn(responders);
        });
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        // The build's ultitools.api.url (env.yml api-url): the agent endpoint is derived from it as in production.
        CloudTestAccess.setBaseUrl(stub.baseUrl());
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        CloudSession.current().invalidate();
        if (features != null) {
            features.shutdown();
        }
        if (responders != null) {
            responders.shutdown();
        }
        stub.close();
        CloudTestAccess.reset();
        MockBukkitHelper.safeUnmock();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** The cloud steps of {@code UltiTools#onEnable}, in its order, on the production bindings. */
    private void startFramework() throws Exception {
        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forPlugin(UltiTools.getInstance());
        bindings.mainThread = mainQueue::add;
        bindings.disabling = () -> !PluginInitiationUtils.isAcceptingPanelMessages();
        bindings.monitor = OutboundTestAccess::newMonitor;
        bindings.mainTimer = task -> {
            mainTimers.add(task);
            return () -> mainTimers.remove(task);
        };
        PluginInitiationUtils.startAcceptingPanelMessages();
        features = CloudFeatures.start(bindings);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.enableCloud();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
    }

    @Test
    @DisplayName("tracer: hello, welcome, telemetry, one command and one log line; no v1 path, no v1 type")
    void aWholeSessionIsV2Only() throws Exception {
        startFramework();

        assertThat(stub.accessLog().count("UPGRADE GET " + AgentEndpoint.AGENT_PATH)).isGreaterThanOrEqualTo(1);
        assertThat(stub.gateway().received(null).get(0).get("type").getAsString()).isEqualTo("hello");
        assertThat(stub.gateway().sent("welcome")).hasSize(1);
        for (String type : new String[] {"status", "players.snapshot", "plugins.snapshot", "worlds.snapshot"}) {
            final String wanted = type;
            await(() -> !stub.gateway().received(wanted).isEmpty(), type);
        }

        String id = stub.gateway().send("command.execute", "say", null, null, null).get("id").getAsString();
        await(() -> !stub.gateway().received("command.result").isEmpty(), "command.result");
        assertThat(stub.gateway().received("command.ack").get(0).get("replyTo").getAsString()).isEqualTo(id);
        JsonObject result = stub.gateway().received("command.result").get(0);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.toString()).contains("hello from the panel");

        streamOneConsoleLine();

        assertOnlyV2Requests();
        assertOnlyContractFrames();
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
        assertThat(stub.accessLog().count("unsupported_type")).isZero();
        assertThat(stub.gateway().sent("error")).isEmpty();
    }

    @Test
    @DisplayName("isFrameworkOwnedType answers from the v2 registry: framework types yes, module keys and v1 names no")
    void frameworkOwnedTypesAreTheV2Types() {
        for (String type : AgentMessageTypes.types()) {
            assertThat(PluginInitiationUtils.isFrameworkOwnedType(type)).as(type).isTrue();
        }
        assertThat(PluginInitiationUtils.isFrameworkOwnedType("ultimail.inbox.list")).isFalse();
        assertThat(PluginInitiationUtils.isFrameworkOwnedType("execute_command")).isFalse();
        assertThat(PluginInitiationUtils.isFrameworkOwnedType("ping")).isFalse();
        assertThat(PluginInitiationUtils.isFrameworkOwnedType("Command.Execute")).isFalse();
        assertThat(PluginInitiationUtils.isFrameworkOwnedType(null)).isFalse();
    }

    @Test
    @DisplayName("resolveFileOperationCapability keeps its 6.3.0 mapping for the v2 operation names")
    void fileOperationCapabilityKeepsItsMapping() {
        assertThat(PluginInitiationUtils.resolveFileOperationCapability("write")).isEqualTo(Capability.FILE_WRITE);
        assertThat(PluginInitiationUtils.resolveFileOperationCapability("delete")).isEqualTo(Capability.FILE_DELETE);
        assertThat(PluginInitiationUtils.resolveFileOperationCapability("read")).isEqualTo(Capability.FILE_READ);
        assertThat(PluginInitiationUtils.resolveFileOperationCapability("list")).isEqualTo(Capability.FILE_READ);
        assertThat(PluginInitiationUtils.resolveFileOperationCapability("chmod")).isEqualTo(Capability.FILE_READ);
        assertThat(PluginInitiationUtils.resolveFileOperationCapability(null)).isEqualTo(Capability.FILE_READ);
    }

    // ------------------------------------------------------------------ helpers

    /** Logs probe lines until the stream is live, then one console line, and waits for it in {@code log.lines}. */
    private void streamOneConsoleLine() throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        int probe = 0;
        while (!logText().contains("v2-only stream probe")) {
            CONSOLE.info("v2-only stream probe " + probe++);
            await(() -> true, "nothing");
            Thread.sleep(200L);
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("the log stream never started");
            }
        }
        CONSOLE.info("A console line on a v2-only session");
        await(() -> logText().contains("A console line on a v2-only session"), "the console line in log.lines");
    }

    /** Every request line names a path of the framework's own v2 clients; none is a v1 path (410). */
    private void assertOnlyV2Requests() {
        assertThat(stub.accessLog().lines()).noneSatisfy(line -> assertThat(line).contains(" V1 "));
        List<String> paths = new ArrayList<>();
        for (String line : stub.accessLog().lines()) {
            Matcher matcher = REQUEST_LINE.matcher(line);
            if (matcher.find()) {
                String target = matcher.group(3);
                int query = target.indexOf('?');
                paths.add(query < 0 ? target : target.substring(0, query));
            }
        }
        assertThat(paths).as("the request lines of the access log").isNotEmpty();
        for (String path : paths) {
            assertThat(isV2ClientPath(path)).as("request path " + path).isTrue();
        }
    }

    /** The paths the framework's clients request: the agent socket (and its revoke below it), device login, catalogue. */
    private static boolean isV2ClientPath(String path) {
        return path.equals(AgentEndpoint.AGENT_PATH)
                || path.startsWith(AgentEndpoint.AGENT_PATH + "/")
                || path.equals(DeviceAuthorizationClient.AUTHORIZE_PATH)
                || path.equals(DeviceAuthorizationClient.TOKEN_PATH)
                || path.equals(CatalogueClient.FRAMEWORK)
                || path.equals(CatalogueClient.MODULES)
                || path.startsWith(CatalogueClient.MODULES + "/");
    }

    /** Every recorded frame is a type of the frozen contract, sent in a direction the contract allows. */
    private void assertOnlyContractFrames() {
        Set<String> up = new HashSet<>();
        Set<String> down = new HashSet<>();
        for (UltiCloudStub.MessageType type : stub.contract().messages()) {
            if (type.up()) {
                up.add(type.type());
            }
            if (type.down()) {
                down.add(type.type());
            }
        }
        List<StubGateway.Frame> frames = stub.gateway().frames();
        assertThat(frames).isNotEmpty();
        for (StubGateway.Frame frame : frames) {
            Set<String> allowed = "in".equals(frame.direction()) ? up : down;
            assertThat(allowed).as(frame.toString()).contains(frame.type());
        }
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (JsonObject frame : stub.gateway().received("log.lines")) {
            for (JsonElement line : frame.getAsJsonObject("payload").getAsJsonArray("lines")) {
                text.append(line.getAsJsonObject().get("message").getAsString()).append('\n');
            }
        }
        return text.toString();
    }

    private void drainMain() {
        Runnable task;
        while ((task = mainQueue.poll()) != null) {
            task.run();
        }
    }

    /** Waits on the test thread with a bounded deadline, running the server-thread work and timers meanwhile. */
    private void await(BooleanSupplier condition, String what) throws InterruptedException {
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

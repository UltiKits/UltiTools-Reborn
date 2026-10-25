package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.RemoteRequestHandlers;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.stub.StubAccessLog;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Plan 22-21 Task 2 on the in-process stub (D-10, D-22, D-24, D-25, S-53, S-65, S-66): every inbound request type of
 * the tracks is registered on the live connection, the hello declares what the operator's switches allow, a switch
 * turned off after the handshake refuses the next request with {@code capability_not_accepted}, player and power
 * actions reach the action log, and a rotated credential is persisted before its acknowledgement and used by the
 * next connection.
 */
@DisplayName("Inbound wiring on the live connection")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the test re-publishes the UltiTools singleton
class InboundWiringTest {

    static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";

    @TempDir
    Path temp;

    private ServerMock server;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<RemoteActionLog.Entry> actions = new CopyOnWriteArrayList<>();
    private final List<String> powerCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger executions = new AtomicInteger();
    /** The server thread's queue: MockBukkit treats the test thread as the server thread, so it drains this. */
    private final java.util.concurrent.BlockingQueue<Runnable> mainQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private Path serverRoot;
    /** Wraps the real files handler of the next {@link #connect()}; identity by default. */
    private UnaryOperator<CloudRequestHandler> filesDecorator = UnaryOperator.identity();
    /** Runs on the handler's thread for every action-log entry, after it is recorded. */
    private Consumer<RemoteActionLog.Entry> actionHook = entry -> { };
    /** Runs on the handler's thread when the fake power executor schedules a stop. */
    private Runnable stopHook = () -> { };

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        serverRoot = temp.resolve("server");
        Path state = serverRoot.resolve(".ultikits");
        Files.createDirectories(state);
        Logger logger = Logger.getLogger("InboundWiringTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { }
            @Override public void flush() { }
            @Override public void close() { }
        });
        config.set(Capability.COMMANDS.getConfigPath(), true);
        config.set(Capability.PLAYER_ACTIONS.getConfigPath(), true);
        config.set(Capability.FILE_WRITE.getConfigPath(), true);
        config.set(Capability.SERVER_PROPERTIES.getConfigPath(), true);
        config.set(Capability.POWER_ACTIONS.getConfigPath(), false);
        TestHelper.mockUltiToolsInstance(plugin -> {
            lenient().when(plugin.getLogger()).thenReturn(logger);
            lenient().when(plugin.getConfig()).thenReturn(config);
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

    private void connect() throws Exception {
        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forTesting();
        CommandExecutionManager commands = mock(CommandExecutionManager.class);
        doAnswer(invocation -> {
            executions.incrementAndGet();
            CommandExecutionManager.CommandResultSink sink = invocation.getArgument(3);
            sink.completed(Collections.singletonList("Done"), false, 3L);
            return null;
        }).when(commands).execute(anyString(), anyString(), any(), any());
        bindings.commands = () -> commands;
        bindings.mainThread = mainQueue::add;
        bindings.disabling = () -> false;
        bindings.power = () -> new PowerActionHandler.PowerExecutor() {
            @Override public boolean restartSupported() { return true; }
            @Override public void scheduleStop() { stopHook.run(); powerCalls.add("stop"); }
            @Override public void scheduleRestart() { powerCalls.add("restart"); }
        };
        bindings.actionLog = entry -> {
            actions.add(entry);
            actionHook.accept(entry);
        };
        FileOperationManager files = new FileOperationManager();
        bindings.files = () -> filesDecorator.apply(RemoteRequestHandlers.files(files, serverRoot.toFile(),
                actions::add));
        bindings.properties = () -> RemoteRequestHandlers.properties(
                new ServerPropertiesManager(serverRoot.toFile()), actions::add);
        bindings.modules = Collections::emptyList;
        bindings.configs = () -> mock(ConfigManager.class);
        PanelResponderRegistry responders = new PanelResponderRegistry();
        responders.registerResponder("ultimail.inbox.list", data -> {
            JsonObject answer = new JsonObject();
            answer.addProperty("count", 0);
            return CompletableFuture.completedFuture(answer);
        }, "UltiMail");
        bindings.responders = () -> responders;
        bindings.moduleVersion = name -> "1.2.0";
        features = CloudFeatures.start(bindings);
        assertThat(PluginInitiationUtils.resumeSavedCredentialOnStartup()).isTrue();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
    }

    private JsonObject reply(String type, int count) throws InterruptedException {
        await(() -> stub.gateway().received(type).size() >= count, type);
        return stub.gateway().received(type).get(count - 1);
    }

    private JsonObject send(String type, String fixture, JsonObject payload) {
        return stub.gateway().send(type, fixture, payload, null, null);
    }

    @Test
    @DisplayName("the hello declares the switched-on names, power, link and the answering module")
    void helloDeclaresTheSwitches() throws Exception {
        connect();
        JsonObject hello = reply("hello", 1);
        List<String> declared = new ArrayList<>();
        hello.getAsJsonObject("payload").getAsJsonArray("capabilities").forEach(e -> declared.add(e.getAsString()));
        assertThat(declared).containsExactly("telemetry", "logs", "events:players", "events:commands", "errors",
                "commands", "players:actions", "files:read", "config:read", "files:write", "config:write",
                "properties:read", "properties:write", "power", "link", "modules:ultimail@1.2.0");
        assertThat(hello.getAsJsonObject("payload").getAsJsonObject("settings").get("powerActionsEnabled")
                .getAsBoolean()).isFalse();
    }

    @Test
    @DisplayName("every inbound request type answers with a schema-valid reply correlated to it")
    void everyTypeAnswers() throws Exception {
        config.set(Capability.FILE_DELETE.getConfigPath(), true);
        connect();
        String id = send("command.execute", "say", null).get("id").getAsString();
        assertThat(reply("command.ack", 1).get("replyTo").getAsString()).isEqualTo(id);
        assertThat(reply("command.result", 1).get("replyTo").getAsString()).isEqualTo(id);

        // MockBukkit discards the kick event's result; the 22-11 double restores Paper's kick semantics.
        server.addPlayer(new PlayerActionEndToEndTest.KickablePlayer(server, "Notch_fan"));
        id = send("player.action", "kick-with-reason", null).get("id").getAsString();
        JsonObject action = reply("player.action.result", 1);
        assertThat(action.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(action.getAsJsonObject("payload").get("status").getAsString()).as(action.toString())
                .isEqualTo("ok");
        await(() -> !actions.isEmpty(), "the action log entry");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getCapability()).isEqualTo(Capability.PLAYER_ACTIONS.name());
        assertThat(actions.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);

        String[][] requests = {
                {"files.read", "config", "files.read.result"},
                {"files.list", "root", "files.list.result"},
                {"files.delete", "file", "files.delete.result"},
                {"properties.get", "all", "properties.get.result"},
                {"properties.update", "motd-and-view-distance", "properties.update.result"},
                {"config.tree", "empty-payload", "config.tree.result"},
                {"config.entries", "file", "config.entries.result"},
                {"config.update", "two-changes", "config.update.result"},
                {"module.request", "ultimail", "module.response"},
        };
        for (String[] request : requests) {
            id = send(request[0], request[1], null).get("id").getAsString();
            assertThat(reply(request[2], 1).get("replyTo").getAsString()).as(request[0]).isEqualTo(id);
        }
        assertThat(reply("module.response", 1).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
        assertThat(stub.accessLog().count("WS-INVALID")).isZero();
        assertThat(stub.accessLog().count("unsupported_type")).isZero();
    }

    @Test
    @DisplayName("ack-first after a restart: a redelivered command is acknowledged again and not run again")
    void redeliveredCommandAfterRestartIsNotRunAgain() throws Exception {
        connect();
        String id = UltiCloudStub.Contract.newUlid();
        stub.gateway().send("command.execute", "say", null, id, null);
        reply("command.result", 1);
        assertThat(reply("command.ack", 1).get("replyTo").getAsString()).isEqualTo(id);

        // Restart: the session flushes and stops, a fresh session loads the state file and reconnects.
        CloudSession.current().invalidate();
        features.shutdown();
        CloudSession.resetForTesting();
        CloudSession.setAgentEndpointOverrideForTesting(new URI("ws://127.0.0.1:" + stub.httpPort() + "/v1/agent"));
        int executedBefore = executions.get();
        connect();
        await(() -> stub.gateway().openConnections() == 1, "one live connection");
        stub.gateway().send("command.execute", "say", null, id, null);
        assertThat(reply("command.ack", 2).get("replyTo").getAsString()).isEqualTo(id);
        Thread.sleep(300);
        assertThat(executions.get()).as("the command is not executed again").isEqualTo(executedBefore);
        assertThat(stub.gateway().received("command.result")).hasSize(1);
    }

    @Test
    @DisplayName("a module the gateway did not accept is refused with capability_not_accepted")
    void undeclaredModuleIsRefused() throws Exception {
        connect();
        JsonObject payload = new JsonObject();
        payload.addProperty("module", "othermod");
        String id = send("module.request", "ultimail", payload).get("id").getAsString();
        JsonObject error = reply("error", 1);
        assertThat(error.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(error.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("capability_not_accepted");
    }

    @Test
    @DisplayName("server.power: switch off answers power_disabled; on acknowledges and schedules the stop")
    void powerFollowsTheSwitch() throws Exception {
        connect();
        String id = send("server.power", "stop", null).get("id").getAsString();
        JsonObject refused = reply("server.power.result", 1);
        assertThat(refused.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(refused.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("power_disabled");
        assertThat(powerCalls).isEmpty();
        assertThat(actions).anySatisfy(entry -> {
            assertThat(entry.getCapability()).isEqualTo(Capability.POWER_ACTIONS.name());
            assertThat(entry.getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        });

        config.set(Capability.POWER_ACTIONS.getConfigPath(), true);
        id = send("server.power", "stop", null).get("id").getAsString();
        assertThat(reply("command.ack", 2).get("replyTo").getAsString()).isEqualTo(id);
        assertThat(reply("server.power.result", 2).getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo("ok");
        await(() -> powerCalls.contains("stop"), "the scheduled stop");
    }

    @Test
    @DisplayName("server.power writes the reliable-delivery state again right before the stop is scheduled")
    void powerFlushesTheStateBeforeTheStop() throws Exception {
        final Path stateFile = serverRoot.resolve(".ultikits").resolve("cloud-outbox.json");
        final AtomicBoolean removedAfterAck = new AtomicBoolean();
        final AtomicReference<Boolean> presentAtStop = new AtomicReference<>();
        // The allowed entry is recorded after the ack gate persisted the id and before the pre-shutdown hook.
        actionHook = entry -> {
            if (Capability.POWER_ACTIONS.name().equals(entry.getCapability())
                    && entry.getVerdict() == RemoteActionLog.Verdict.ALLOWED) {
                try {
                    removedAfterAck.set(Files.deleteIfExists(stateFile));
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        stopHook = () -> presentAtStop.set(Files.exists(stateFile));
        config.set(Capability.POWER_ACTIONS.getConfigPath(), true);
        connect();
        send("server.power", "stop", null);
        await(() -> powerCalls.contains("stop"), "the scheduled stop");
        assertThat(removedAfterAck.get()).as("control: the ack gate had written the state file").isTrue();
        assertThat(presentAtStop.get()).as("the pre-shutdown flush wrote it again before the stop").isTrue();
    }

    @Test
    @DisplayName("a switch turned off after the handshake refuses the next request and writes nothing (S-66)")
    void switchOffAfterHandshakeRefuses() throws Exception {
        connect();
        config.set(Capability.FILE_WRITE.getConfigPath(), false);
        String id = send("files.write", "with-base-version", null).get("id").getAsString();
        JsonObject error = reply("error", 1);
        assertThat(error.get("replyTo").getAsString()).isEqualTo(id);
        JsonObject payload = error.getAsJsonObject("payload");
        assertThat(payload.get("code").getAsString()).isEqualTo("capability_not_accepted");
        assertThat(payload.getAsJsonObject("details").get("capability").getAsString()).isEqualTo("files:write");
        assertThat(payload.get("message").getAsString()).contains(Capability.FILE_WRITE.getConfigPath());
        assertThat(stub.gateway().received("files.write.result")).isEmpty();
        assertThat(new File(System.getProperty("user.dir"), "plugins/UltiChat/config.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a files.list held on a slow disk does not delay a command.ack sent meanwhile")
    void slowFileRequestDoesNotDelayOtherRequests() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        filesDecorator = real -> new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel reply) {
                entered.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                real.handle(request, reply);
            }
        };
        connect();
        String list = send("files.list", "root", null).get("id").getAsString();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("the listing started").isTrue();
        try {
            String command = send("command.execute", "say", null).get("id").getAsString();
            assertThat(reply("command.ack", 1).get("replyTo").getAsString()).isEqualTo(command);
            assertThat(stub.gateway().received("files.list.result")).as("the listing is still running").isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(reply("files.list.result", 1).get("replyTo").getAsString()).isEqualTo(list);
    }

    @Test
    @DisplayName("credential.rotate persists before the ack, owner-only, and the next connection uses it")
    void rotationPersistsBeforeTheAck() throws Exception {
        connect();
        JsonObject sent = send("credential.rotate", "default-grace", null);
        String id = sent.get("id").getAsString();
        assertThat(reply("credential.rotate.ack", 1).get("replyTo").getAsString()).isEqualTo(id);
        String rotated = "ucs_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Path file = serverRoot.resolve(".ultikits").resolve("credentials.json");
        JsonObject stored = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject(ServerCredentialStore.KEY);
        assertThat(stored.get("credential").getAsString()).isEqualTo(rotated);
        assertThat(stored.get("credentialId").getAsString()).isEqualTo("01JB2Z8Y3K4M5N6P7Q8R9S0CRD");
        assertThat(stored.get("serverId").getAsString()).isEqualTo(SERVER_ID);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");

        int upgradesBefore = stub.accessLog().count("-> 101");
        stub.closeLive(1001, "going away");
        String fingerprint = StubAccessLog.fingerprint(rotated);
        await(() -> stub.accessLog().count("-> 101") > upgradesBefore, "the reconnect");
        assertThat(stub.accessLog().lines()).anySatisfy(line -> {
            assertThat(line).contains("UPGRADE").contains(fingerprint).contains("-> 101");
        });
    }

    @Test
    @DisplayName("the production bindings bind every port and send action-log entries to the RemoteActionLog")
    void productionBindingsAreComplete() {
        UltiTools plugin = UltiTools.getInstance();
        RemoteActionLog log = mock(RemoteActionLog.class);
        lenient().when(plugin.getRemoteActionLog()).thenReturn(log);
        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forPlugin(plugin);
        assertThat(bindings.commands).isNotNull();
        assertThat(bindings.mainThread).isNotNull();
        assertThat(bindings.disabling).isNotNull();
        assertThat(bindings.power).isNotNull();
        assertThat(bindings.files).isNotNull();
        assertThat(bindings.properties).isNotNull();
        assertThat(bindings.modules).isNotNull();
        assertThat(bindings.configs).isNotNull();
        assertThat(bindings.responders).isNotNull();
        assertThat(bindings.moduleVersion).isNotNull();
        RemoteActionLog.Entry entry = RemoteActionLog.Entry.allowed(Capability.PLAYER_ACTIONS, "player.action.kick",
                "Steve", "panel");
        bindings.actionLog.accept(entry);
        verify(log).record(entry);
        AtomicInteger thrown = new AtomicInteger();
        doAnswer(invocation -> {
            thrown.incrementAndGet();
            throw new IllegalStateException("log closed");
        }).when(log).record(any());
        bindings.actionLog.accept(entry);
        assertThat(thrown.get()).as("a failing action log never reaches the handler").isEqualTo(1);
    }

    /** Waits on the test thread, running the server-thread work the handlers queued meanwhile. */
    private void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000L;
        while (!condition.getAsBoolean()) {
            Runnable task;
            while ((task = mainQueue.poll()) != null) {
                task.run();
            }
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }
}

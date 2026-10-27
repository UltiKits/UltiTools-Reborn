package com.ultikits.ultitools.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.mockbukkit.mockbukkit.ServerMock;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.manager.OutboundTestAccess;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.RemoteRequestHandlers;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * Test scope (plan 22-23): one protocol v2 session of the framework on the in-process {@link UltiCloudStub}, for the
 * tests that replaced the protocol v1 inbound-dispatch tests, including those outside this package. The framework is
 * a mocked {@link UltiTools} whose cloud ports are the production bindings ({@link CloudFeatures.Bindings#forPlugin})
 * with the file and property handlers rooted in a temporary server directory, a telemetry monitor, and a command
 * manager whose execution is counted (Paper's capturing command sender does not exist in MockBukkit) unless a real
 * one is requested. The server thread is either a queue the waiting test thread drains (the default) or the
 * production hop through {@code PluginInitiationUtils#scheduleUnlessDisabling} and the Bukkit scheduler of the
 * {@link ServerMock} the test passes in.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // close() re-publishes the UltiTools singleton
public final class V2StubSession implements AutoCloseable {

    /** The server id the stub's welcome carries. */
    public static final String SERVER_ID = "01JTESTSERVER0000000000000";
    private static final String CREDENTIAL = "ucs_TESTSECRET";
    private static final long WAIT_MS = 30_000L;
    /**
     * The start of {@code handler.ConsoleMirror}'s WARNING that Paper's log forwarder is missing. MockBukkit has no
     * {@code org.bukkit.craftbukkit.util.ForwardLogHandler}, so the first mirror install in a JVM always logs it, and
     * only once per JVM (its once-per-run flag), so which test sees it depends on test order (CI run 40419620913:
     * #677's first test after {@code ConsoleMirrorForwarderMissingTest} reset the flag). It says nothing about the
     * framework's cloud path; {@code ConsoleMirrorForwarderMissingTest} pins the warning itself.
     */
    static final String FORWARDER_MISSING_WARNING =
            "[UltiPanel] Paper's log forwarder (org.bukkit.craftbukkit.util.ForwardLogHandler) was not found";

    private final Path temp;
    private final ServerMock server;
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<LogRecord> pluginLog = new CopyOnWriteArrayList<>();
    private final List<RemoteActionLog.Entry> actions = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
    private final List<Runnable> mainTimers = new CopyOnWriteArrayList<>();
    private final AtomicInteger executions = new AtomicInteger();
    private final PanelResponderRegistry responders = new PanelResponderRegistry();
    private boolean productionMainThread;
    private boolean realCommands;
    private long lastTimerRun;
    private UltiCloudStub.Handle stub;
    private CloudFeatures features;
    private UltiTools plugin;

    /**
     * @param temp   a temporary directory for the server root, the credentials and the access log
     * @param server the MockBukkit server the test started
     */
    public V2StubSession(Path temp, ServerMock server) {
        this.temp = temp;
        this.server = server;
    }

    /** @return the framework's {@code config.yml}; set switches before or during the session */
    public YamlConfiguration config() {
        return config;
    }

    /** Runs server-thread work through the production hop and the server's Bukkit scheduler. */
    public V2StubSession productionMainThread() {
        productionMainThread = true;
        return this;
    }

    /** Binds a real {@link CommandExecutionManager} instead of the counting one. */
    public V2StubSession realCommands() {
        realCommands = true;
        return this;
    }

    /** @return the responder registry {@code module.request} is answered from */
    public PanelResponderRegistry responders() {
        return responders;
    }

    /**
     * Starts the framework's cloud steps in {@code UltiTools#onEnable}'s order and waits until it is connected.
     *
     * @param extraStubbing more stubbing of the mocked plugin before it is published, or {@code null}
     * @throws Exception when the stub or the connection cannot be started
     */
    public void start(Consumer<UltiTools> extraStubbing) throws Exception {
        Path serverRoot = serverRoot();
        Path state = serverRoot.resolve(".ultikits");
        Files.createDirectories(state);
        Logger logger = Logger.getLogger("V2StubSession." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (!isForwarderMissingWarning(record)) {
                    pluginLog.add(record);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        // Built before the plugin is published (TestHelper, #250); a real manager reads the default blocklist.
        final CommandExecutionManager commands = realCommands ? new CommandExecutionManager() : countingCommands();
        final RemoteActionLog actionLog = mock(RemoteActionLog.class);
        doAnswer(invocation -> {
            actions.add(invocation.getArgument(0));
            return null;
        }).when(actionLog).record(any());
        plugin = TestHelper.mockUltiToolsInstance(mocked -> {
            lenient().when(mocked.getLogger()).thenReturn(logger);
            lenient().when(mocked.getConfig()).thenReturn(config);
            lenient().when(mocked.getDescription()).thenReturn(new PluginDescriptionFile(
                    "UltiTools", "6.3.1-SNAPSHOT", "com.ultikits.ultitools.UltiTools"));
            lenient().when(mocked.getRemoteActionLog()).thenReturn(actionLog);
            lenient().when(mocked.getPanelResponderRegistry()).thenReturn(responders);
            lenient().when(mocked.getCommandExecutionManager()).thenReturn(commands);
            if (extraStubbing != null) {
                extraStubbing.accept(mocked);
            }
        });
        CloudTestAccess.useTemporaryCredentials(state);
        stub = UltiCloudStub.start(UltiCloudStub.Options.defaults().accessLog(temp.resolve("access.log"))
                .acceptCredential(CREDENTIAL, SERVER_ID));
        CloudTestAccess.setBaseUrl(stub.baseUrl());
        CloudTestAccess.storeLinkedCredential("Survival", SERVER_ID);

        CloudFeatures.Bindings bindings = CloudFeatures.Bindings.forPlugin(plugin);
        if (!productionMainThread) {
            bindings.mainThread = mainQueue::add;
            bindings.mainTimer = task -> {
                mainTimers.add(task);
                return () -> mainTimers.remove(task);
            };
        }
        bindings.monitor = OutboundTestAccess::newMonitor;
        bindings.files = () -> RemoteRequestHandlers.files(new FileOperationManager(), serverRoot.toFile(),
                bindings.actionLog);
        bindings.properties = () -> RemoteRequestHandlers.properties(
                new ServerPropertiesManager(serverRoot.toFile()), bindings.actionLog);
        bindings.modules = Collections::emptyList;
        bindings.configs = () -> mock(ConfigManager.class);
        bindings.moduleVersion = name -> "1.2.0";
        PluginInitiationUtils.startAcceptingPanelMessages();
        features = CloudFeatures.start(bindings);
        if (!PluginInitiationUtils.resumeSavedCredentialOnStartup()) {
            throw new IllegalStateException("the stored credential was not adopted");
        }
        PluginInitiationUtils.enableCloud();
        PluginInitiationUtils.initWebsocket();
        await(() -> CloudAuthManager.status().isConnected(), "connected");
    }

    private CommandExecutionManager countingCommands() {
        CommandExecutionManager commands = mock(CommandExecutionManager.class);
        doAnswer(invocation -> {
            Executor dispatcher = invocation.getArgument(2);
            CommandExecutionManager.CommandResultSink sink = invocation.getArgument(3);
            dispatcher.execute(() -> {
                executions.incrementAndGet();
                sink.completed(Collections.singletonList("Done"), false, 1L);
            });
            return null;
        }).when(commands).execute(anyString(), anyString(), any(), any());
        return commands;
    }

    /** @return the mocked framework plugin, for stubbing {@code isEnabled()} */
    public UltiTools plugin() {
        return plugin;
    }

    /** @return the stub */
    public UltiCloudStub.Handle stub() {
        return stub;
    }

    /** @return the temporary server root the file and property handlers work in */
    public Path serverRoot() {
        return temp.resolve("server");
    }

    /** @return how many commands the counting command manager executed on the server thread */
    public int executions() {
        return executions.get();
    }

    /** @return every action-log entry recorded so far */
    public List<RemoteActionLog.Entry> actions() {
        return actions;
    }

    /**
     * @return every record the framework logged through its plugin logger, except the MockBukkit-only
     *         {@link #FORWARDER_MISSING_WARNING}, whose presence depends on test order
     */
    public List<LogRecord> pluginLog() {
        return pluginLog;
    }

    private static boolean isForwarderMissingWarning(LogRecord record) {
        String message = record.getMessage();
        return Level.WARNING.equals(record.getLevel()) && message != null
                && message.startsWith(FORWARDER_MISSING_WARNING);
    }

    /**
     * Sends one gateway frame built from its first (or the named) valid fixture.
     *
     * @param type    the type
     * @param fixture the fixture name, or {@code null} for the first
     * @param payload payload overrides, or {@code null}
     * @return the frame as sent
     */
    public JsonObject send(String type, String fixture, JsonObject payload) {
        return stub.gateway().send(type, fixture, payload, null, null);
    }

    /**
     * Waits for the {@code count}-th received frame of {@code type}.
     *
     * @param type  the type
     * @param count which one, from 1
     * @return that frame
     * @throws InterruptedException when interrupted
     */
    public JsonObject reply(String type, int count) throws InterruptedException {
        await(() -> stub.gateway().received(type).size() >= count, type);
        return stub.gateway().received(type).get(count - 1);
    }

    /**
     * Waits with a bounded deadline, running the server-thread work meanwhile.
     *
     * @param condition what to wait for
     * @param what      for the failure message
     * @throws InterruptedException when interrupted
     */
    public void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            runServerThread();
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    /**
     * Lets {@code millis} pass while running the server-thread work, for a negative assertion that follows a
     * positive one.
     *
     * @param millis how long
     * @throws InterruptedException when interrupted
     */
    public void pass(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            runServerThread();
            Thread.sleep(20);
        }
        runServerThread();
    }

    private void runServerThread() {
        if (productionMainThread) {
            server.getScheduler().performOneTick();
            return;
        }
        Runnable task;
        while ((task = mainQueue.poll()) != null) {
            task.run();
        }
        long now = System.currentTimeMillis();
        if (now - lastTimerRun >= 1000L) {
            lastTimerRun = now;
            for (Runnable timer : mainTimers) {
                timer.run();
            }
        }
    }

    @Override
    public void close() throws Exception {
        PluginInitiationUtils.startAcceptingPanelMessages();
        CloudSession.current().invalidate();
        if (features != null) {
            features.shutdown();
        }
        responders.shutdown();
        if (stub != null) {
            stub.close();
        }
        CloudTestAccess.reset();
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}

package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.AgentOutboundFeeds;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.RemoteRequestHandlers;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;

/**
 * The inbound UltiCloud features of one enabled plugin (plan 22-21, D-26, S-59, S-60); internal, not for module use.
 * <p>
 * <b>Two lifetimes.</b> The objects a module or a redelivery depends on live as long as the plugin is enabled: the
 * {@link CloudPlayerLinks} API and the {@link PlayerLinkChannel} behind it (so a handler a module set at startup
 * survives every reconnect), and the one {@link SessionDedupeLedger} every handler shares (so the ledger monitor is
 * the same across connections). Each upgraded connection gets its own {@link AgentProtocol}; {@link CloudSession}
 * calls {@link #register(AgentMessageRouter, CloudChannel, AgentProtocol, CredentialRotationHandler.CredentialPersister,
 * ScheduledExecutorService)}
 * once for it, which registers the request handlers on that connection's router and points the lifetime objects at
 * that connection. A {@code credential.rotate} is persisted through the session that owns the connection, never
 * through the store directly, because that session is the one owner of the current credential.
 * <p>
 * <b>Threads.</b> The player-link handler of a module runs on one daemon thread, {@code UltiTools-CloudLinks}; the
 * create and handler deadlines run on a second, {@code UltiTools-CloudLinks-Timer}. Both stop in {@link #shutdown()}.
 * The file and {@code server.properties} handlers do blocking disk I/O, so each connection gets one
 * {@link SerialRequestWorker} ({@code UltiTools-CloudFiles}, daemon, bounded queue, overflow answered
 * {@code rate_limited}) that runs them in arrival order; heartbeats, acks and every other request never wait behind a
 * slow listing. The worker closes with its connection (on close, on the next connection, and in {@link #shutdown()}).
 * The router dispatches every other request on the agent thread, which is never the server thread; the handlers that
 * call the Bukkit API hand that work to the server thread through {@link Bindings#mainThread}.
 * <p>
 * <b>Registered request types</b> (each only when its port is bound; {@link Bindings#forPlugin} binds every one):
 * {@code command.execute} (22-09), {@code server.power} (22-10), {@code player.action} (22-11), {@code files.*} and
 * {@code properties.*} (22-14), {@code config.*} (22-15), {@code module.request} (22-16), and
 * {@code player.link.created/completed/expired} and {@code credential.rotate} (22-17).
 * <p>
 * <b>Capabilities.</b> {@link AgentCapabilities} decides the hello list and the router's per-request switch check
 * (S-65, S-66). A {@code module.request} is also filtered by the module capabilities the gateway accepted: a request
 * for a module whose {@code modules:<id>@<X.Y.Z>} name was not accepted is answered {@code capability_not_accepted}
 * (contract SEMANTICS 8) and never reaches the module.
 * <p>
 * <b>Outbound (plan 22-22).</b> One {@link AgentOutboundFeeds} lives as long as the plugin is enabled. On every
 * established connection it gets the live channel; the connection's agent scheduler then runs its once-a-second send
 * (log lines) until the close, and a main-thread task every 20 ticks, started at the first connection, samples TPS,
 * CPU and the server state and advances the telemetry cadence. {@code snapshot.request} is registered on every
 * connection. A {@code /ul reload} that changes an operator setting sends {@code settings.changed} once
 * ({@link #onReload()}).
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class CloudFeatures {

    /** The name of the thread the module's player-link handler runs on. */
    static final String HANDLER_THREAD_NAME = "UltiTools-CloudLinks";

    private static final String PANEL_ACTOR = "panel";
    private static final String MODULE_REQUEST = "module.request";
    private static final String MODULE_PREFIX = "modules:";

    /** How often the agent thread sends the buffered log lines (contract: every second, or when a batch is full). */
    static final long SEND_PERIOD_MS = 1000L;

    private static final String SETTINGS_CHANGED = "settings.changed";

    private static volatile CloudFeatures installed;

    private final Bindings bindings;
    private final AgentCapabilities capabilities;
    private final AgentSettings settings;
    private final ExecutorService handlerThread;
    private final ScheduledExecutorService timers;
    private final SessionDedupeLedger ledger;
    private final PlayerLinkChannel playerLinkChannel;
    private final CloudPlayerLinks playerLinks;

    /** The protocol of the connection the lifetime objects currently use, or {@code null}. */
    private volatile AgentProtocol protocol;

    /** The file-request worker of the current connection, or {@code null}. */
    private volatile SerialRequestWorker fileWorker;

    private final AgentOutboundFeeds outbound;

    /** Cancels the main-thread sampling task, or {@code null} before the first connection. Guarded by {@code this}. */
    private Runnable mainTimerCancel;

    private CloudFeatures(Bindings bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.handlerThread = Executors.newSingleThreadExecutor(daemon(HANDLER_THREAD_NAME));
        this.timers = Executors.newSingleThreadScheduledExecutor(daemon(HANDLER_THREAD_NAME + "-Timer"));
        this.ledger = new SessionDedupeLedger(() -> CloudSession.current().ledgerTarget());
        this.playerLinkChannel = new PlayerLinkChannel(this::liveChannel, ledger, bindings.onlineMode,
                this::gatewayClockOffsetMillis, timers, handlerThread);
        this.playerLinks = new CloudPlayerLinks(playerLinkChannel);
        this.capabilities = new AgentCapabilities(bindings.switches, bindings.errorReporting, this::moduleNames);
        this.settings = new AgentSettings(bindings.powerEnabled);
        ServerMonitorManager monitor = bindings.monitor == null ? null : bindings.monitor.get();
        this.outbound = new AgentOutboundFeeds(monitor, () -> capabilities.enabled("telemetry"),
                () -> capabilities.enabled("logs"), () -> capabilities.enabled("errors"),
                bindings.errors == null ? () -> null : bindings.errors, bindings.clock, bindings.mainThread);
    }

    /**
     * Creates the features of the enabling plugin and makes them the ones every new connection registers. Called once
     * per enable, before any module loads, so a module can set its player-link handler while it enables.
     *
     * @param plugin the enabling framework plugin
     * @return the features, until {@link #shutdown()}
     */
    public static CloudFeatures start(UltiTools plugin) {
        return start(Bindings.forPlugin(Objects.requireNonNull(plugin, "plugin")));
    }

    /**
     * @param bindings the ports the handlers are built from
     * @return new features, installed for every later connection
     */
    static CloudFeatures start(Bindings bindings) {
        CloudFeatures features = new CloudFeatures(bindings);
        installed = features;
        return features;
    }

    /** @return the features new connections register, or {@code null} before the plugin enabled or after it stopped */
    static CloudFeatures installed() {
        return installed;
    }

    /** @return the player-link API; the same object for the whole enabled lifetime, never {@code null} */
    public CloudPlayerLinks playerLinks() {
        return playerLinks;
    }

    /**
     * Stops the player-link threads and answers every pending create as not connected. New connections no longer
     * register these features. Never waits for a thread.
     */
    public void shutdown() {
        if (installed == this) {
            installed = null;
        }
        protocol = null;
        SerialRequestWorker worker = fileWorker;
        fileWorker = null;
        if (worker != null) {
            worker.close();
        }
        playerLinkChannel.onChannelClosed();
        handlerThread.shutdown();
        timers.shutdown();
        Runnable cancel;
        synchronized (this) {
            cancel = mainTimerCancel;
            mainTimerCancel = null;
        }
        if (cancel != null) {
            cancel.run();
        }
        outbound.shutdown();
    }

    /**
     * Registers the inbound handlers on one connection's router and points the lifetime objects at that connection.
     * Called by {@link CloudSession} once for each upgraded socket, before its {@code hello}.
     *
     * @param router   the connection's router
     * @param channel  the connection's live channel (open between welcome and close)
     * @param protocol the connection's protocol layer
     * @param rotation persists a {@code credential.rotate} through the session that owns this connection
     * @param agent    the connection's agent scheduler: runs the once-a-second send while it is established
     */
    void register(AgentMessageRouter router, CloudChannel channel, final AgentProtocol protocol,
                  CredentialRotationHandler.CredentialPersister rotation, final ScheduledExecutorService agent) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(agent, "agent");
        this.protocol = protocol;
        final SerialRequestWorker worker = new SerialRequestWorker(bindings.fileQueueCapacity);
        SerialRequestWorker previous = fileWorker;
        fileWorker = worker;
        if (previous != null) {
            previous.close();
        }
        registerCommands(router);
        registerPower(router);
        registerPlayerActions(router);
        registerAll(router, RemoteRequestHandlers.FILE_TYPES, bindings.files, worker);
        registerAll(router, RemoteRequestHandlers.PROPERTY_TYPES, bindings.properties, worker);
        registerConfig(router);
        registerModules(router, protocol);
        router.register("credential.rotate", new CredentialRotationHandler(rotation));
        playerLinkChannel.register(router);
        CloudRequestHandler snapshots = outbound.snapshotRequests();
        if (snapshots != null) {
            router.register(AgentOutboundFeeds.SNAPSHOT_REQUEST, snapshots);
        }
        // The protocol layer consumes inbound error frames itself; the link channel hears them as reply listener.
        protocol.addReplyListener(playerLinkChannel::onErrorReply);
        protocol.addLifecycleListener(new AgentProtocol.Listener() {
            private ScheduledFuture<?> sending;

            @Override
            public void established(CloudChannel live) {
                if (CloudFeatures.this.protocol != protocol) {
                    return;
                }
                outbound.established(live, agent);
                reportSettingsIfChanged(live);
                startMainTimer();
                try {
                    sending = agent.scheduleAtFixedRate(outbound::agentSecond, SEND_PERIOD_MS, SEND_PERIOD_MS,
                            TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException stopping) {
                    sending = null;
                }
            }

            @Override
            public void closed() {
                worker.close();
                ScheduledFuture<?> task = sending;
                sending = null;
                if (task != null) {
                    task.cancel(false);
                }
                outbound.closed(channel);
                if (CloudFeatures.this.protocol == protocol) {
                    playerLinkChannel.onChannelClosed();
                }
            }
        });
    }

    /** Starts the main-thread sampling task once; it runs until {@link #shutdown()}, so TPS history has no gaps. */
    private void startMainTimer() {
        if (bindings.mainTimer == null) {
            return;
        }
        synchronized (this) {
            if (mainTimerCancel != null || installed != this) {
                return;
            }
            mainTimerCancel = bindings.mainTimer.apply(outbound::mainThreadSecond);
        }
    }

    /**
     * Sends one {@code player.command} carrying only the command's normalised name (D-20), while a connection is
     * established and the {@code events:commands} switch is on; never buffered (D-14).
     *
     * @param commandLine the command line as the player typed it
     */
    void sendPlayerCommand(String commandLine) {
        if (!capabilities.enabled("events:commands")) {
            return;
        }
        CloudChannel channel = liveChannel();
        if (channel == null || !channel.isOpen()) {
            return;
        }
        JsonObject payload = AgentOutboundFeeds.playerCommandPayload(commandLine);
        if (payload == null) {
            PanelConnectionLog.log(Level.FINE, "UltiCloud: a player command whose name the contract cannot carry "
                    + "was not reported.");
            return;
        }
        channel.send("player.command", payload);
    }

    /** @return the hello list and the per-request switch check of every connection */
    AgentCapabilities capabilities() {
        return capabilities;
    }

    /**
     * The hello's operator settings ({@code powerActionsEnabled}), remembered as reported so a later reload reports
     * only a change ({@code settings.changed}, plan 22-22).
     *
     * @return the settings the next hello carries
     */
    JsonObject helloSettings() {
        synchronized (settings) {
            JsonObject current = settings.helloSettings();
            settings.markReported(current);
            return current;
        }
    }

    /**
     * Reports a changed operator setting on the open connection (plan 22-22, S-53): called by {@code /ul reload} after
     * the framework configuration was read again. Exactly one {@code settings.changed { powerActionsEnabled }} is sent
     * when the value differs from the last one the panel was told (by a hello or an earlier {@code settings.changed});
     * nothing when it is unchanged or no connection is established, in which case the next hello carries the value.
     * Reads the configuration only; never writes an operator file (D-31).
     */
    public void onReload() {
        reportSettingsIfChanged(liveChannel());
    }

    /**
     * The comparison and the mark happen under one monitor, with the hello's own mark, so a reload racing a hello can
     * neither repeat a value nor lose one. Also run when a connection is established, which reports a change made
     * between its hello and its welcome.
     */
    private void reportSettingsIfChanged(CloudChannel channel) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        synchronized (settings) {
            JsonObject changed = settings.changedSinceLastReport();
            if (changed != null && channel.send(SETTINGS_CHANGED, changed)) {
                settings.markReported(changed);
            }
        }
    }

    /** @return the operator settings reporter shared by every connection */
    AgentSettings settings() {
        return settings;
    }

    private void registerCommands(AgentMessageRouter router) {
        if (bindings.commands == null || bindings.mainThread == null || bindings.disabling == null) {
            return;
        }
        CommandExecutionManager manager = bindings.commands.get();
        if (manager != null) {
            router.register("command.execute",
                    new RemoteCommandHandler(manager, ledger, bindings.mainThread, bindings.disabling));
        }
    }

    private void registerPower(AgentMessageRouter router) {
        if (bindings.power == null) {
            return;
        }
        router.register("server.power", new PowerActionHandler(ledger, bindings.powerEnabled, bindings.power.get(),
                this::persistBeforeShutdown, this::recordAction));
    }

    private void registerPlayerActions(AgentMessageRouter router) {
        if (bindings.mainThread == null) {
            return;
        }
        router.register(PlayerActionHandler.TYPE, new PlayerActionHandler(bindings.mainThread,
                new PlayerActionHandler.ActionAudit() {
                    @Override
                    public void allowed(String action, String player) {
                        recordAction(RemoteActionLog.Entry.allowed(Capability.PLAYER_ACTIONS,
                                PlayerActionHandler.TYPE + "." + action, player, PANEL_ACTOR));
                    }

                    @Override
                    public void denied(String action, String player, String reason) {
                        recordAction(RemoteActionLog.Entry.denied(Capability.PLAYER_ACTIONS,
                                PlayerActionHandler.TYPE + "." + action, player, PANEL_ACTOR, reason));
                    }
                }));
    }

    private static void registerAll(AgentMessageRouter router, List<String> types,
                                    Supplier<CloudRequestHandler> factory, SerialRequestWorker worker) {
        if (factory == null) {
            return;
        }
        CloudRequestHandler handler = factory.get();
        if (handler == null) {
            return;
        }
        CloudRequestHandler offThread = worker.wrap(handler);
        for (String type : types) {
            router.register(type, offThread);
        }
    }

    private void registerConfig(AgentMessageRouter router) {
        if (bindings.modules == null || bindings.configs == null || bindings.mainThread == null) {
            return;
        }
        ConfigManager configs = bindings.configs.get();
        if (configs != null) {
            new RemoteConfigHandler(bindings.modules, configs, bindings.mainThread).register(router);
        }
    }

    private void registerModules(AgentMessageRouter router, final AgentProtocol connection) {
        if (bindings.responders == null) {
            return;
        }
        PanelResponderRegistry registry = bindings.responders.get();
        if (registry == null) {
            return;
        }
        final ModuleRequestHandler modules = new ModuleRequestHandler(registry);
        router.register(MODULE_REQUEST, new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel reply) {
                String module = moduleOf(request.payload());
                if (module != null && !accepted(connection.acceptedCapabilities(), module)) {
                    JsonObject details = new JsonObject();
                    details.addProperty("capability", MODULE_PREFIX + module);
                    reply.replyError(request.id(), "error", "capability_not_accepted",
                            "UltiCloud did not accept this module at the handshake.", details);
                    return;
                }
                modules.handle(request, reply);
            }
        });
    }

    private static String moduleOf(JsonObject payload) {
        JsonElement module = payload == null ? null : payload.get("module");
        return module != null && module.isJsonPrimitive() && module.getAsJsonPrimitive().isString()
                ? module.getAsString() : null;
    }

    private static boolean accepted(Set<String> acceptedCapabilities, String module) {
        String prefix = MODULE_PREFIX + module + "@";
        for (String name : acceptedCapabilities) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> moduleNames() {
        if (bindings.responders == null || bindings.moduleVersion == null) {
            return Collections.emptyList();
        }
        PanelResponderRegistry registry = bindings.responders.get();
        return registry == null ? Collections.<String>emptyList()
                : ModuleCapabilities.names(registry, bindings.moduleVersion);
    }

    /** The power handler's pre-shutdown hook: the outbox and the ids reach the disk before the server stops. */
    private void persistBeforeShutdown() {
        synchronized (ledger) {
            try {
                ledger.persistNow();
            } catch (IOException failure) {
                PanelConnectionLog.log(Level.WARNING, "UltiCloud: the reliable delivery state could not be saved "
                        + "before the power action; buffered player join/leave events may be lost.");
            }
        }
    }

    /** One action-log entry; a failing log never reaches the handler, so the request still gets its reply. */
    private void recordAction(RemoteActionLog.Entry entry) {
        Consumer<RemoteActionLog.Entry> log = bindings.actionLog;
        if (log == null) {
            return;
        }
        try {
            log.accept(entry);
        } catch (RuntimeException failure) {
            PanelConnectionLog.log(Level.WARNING, "UltiCloud: a remote action could not be written to the action "
                    + "log (" + failure.getClass().getSimpleName() + ").");
        }
    }

    /** @return the ledger every handler of every connection shares */
    DedupeLedger ledger() {
        return ledger;
    }

    /** @return the bindings these features were built from */
    Bindings bindings() {
        return bindings;
    }

    private CloudChannel liveChannel() {
        AgentProtocol current = protocol;
        return current == null ? null : current.channel();
    }

    private long gatewayClockOffsetMillis() {
        AgentProtocol current = protocol;
        return current == null ? 0L : current.gatewayClockOffsetMillis();
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * The ports the inbound handlers are built from. {@link #forPlugin(UltiTools)} binds every one of them to the
     * running server; a test binds only those it exercises.
     */
    static final class Bindings {
        /** This server's online-mode setting, for {@code player.link.create}. */
        BooleanSupplier onlineMode = () -> false;
        /** Whether an operator switch is on now; read for every hello and every request. */
        Predicate<Capability> switches = Capability::isEnabled;
        /** Whether {@value AgentCapabilities#ERROR_REPORTING_KEY} is on now. */
        BooleanSupplier errorReporting = Bindings::errorReportingEnabled;
        /** The {@code power-actions} switch, read on every {@code server.power}. */
        BooleanSupplier powerEnabled = Capability.POWER_ACTIONS::isEnabled;
        /** The command manager of {@code command.execute}. */
        Supplier<CommandExecutionManager> commands;
        /** Runs Bukkit work on the server thread, in submission order. */
        Executor mainThread;
        /** Whether the plugin is being disabled. */
        BooleanSupplier disabling;
        /** The stop and restart of {@code server.power}. */
        Supplier<PowerActionHandler.PowerExecutor> power;
        /** Receives one entry per remote player or power action. */
        Consumer<RemoteActionLog.Entry> actionLog;
        /** How many file and property requests may wait behind the running one (then {@code rate_limited}). */
        int fileQueueCapacity = SerialRequestWorker.DEFAULT_CAPACITY;
        /** The handler of {@code files.*}. */
        Supplier<CloudRequestHandler> files;
        /** The handler of {@code properties.*}. */
        Supplier<CloudRequestHandler> properties;
        /** The loaded modules whose configuration {@code config.*} serves. */
        Supplier<List<UltiToolsPlugin>> modules;
        /** The configuration manager of {@code config.*}. */
        Supplier<ConfigManager> configs;
        /** The registry {@code module.request} is answered from. */
        Supplier<PanelResponderRegistry> responders;
        /** A loaded module's {@code plugin.yml} version by module name, for its capability name. */
        Function<String, String> moduleVersion;
        /** The collector the {@code error.report} frames are drained from; {@code null} sends none. */
        Supplier<com.ultikits.ultitools.manager.ErrorReportCollector> errors;
        /** The server sampler of the telemetry; {@code null} sends no telemetry. */
        Supplier<ServerMonitorManager> monitor;
        /**
         * Starts a task on the server thread every 20 ticks and returns what cancels it; {@code null} starts none.
         * Ticks, not a wall-clock hop, so the TPS sample measures the server.
         */
        Function<Runnable, Runnable> mainTimer;
        /** Epoch milliseconds for the telemetry cadence and the log rate bucket. */
        LongSupplier clock = System::currentTimeMillis;

        /** @return bindings with no server behind them: only the switches, the credential rotation and links */
        static Bindings forTesting() {
            return new Bindings();
        }

        /**
         * @param plugin the running framework plugin
         * @return every port bound to the running server
         */
        static Bindings forPlugin(final UltiTools plugin) {
            Bindings bindings = new Bindings();
            bindings.onlineMode = () -> Bukkit.getServer() != null && Bukkit.getOnlineMode();
            bindings.commands = plugin::getCommandExecutionManager;
            bindings.mainThread = runnable -> PluginInitiationUtils.scheduleUnlessDisabling("UltiCloud request",
                    () -> Bukkit.getScheduler().runTask(plugin, runnable));
            bindings.disabling = () -> !PluginInitiationUtils.isAcceptingPanelMessages();
            bindings.monitor = plugin::getServerMonitorManager;
            bindings.errors = plugin::getErrorReportCollector;
            bindings.mainTimer = task -> {
                final org.bukkit.scheduler.BukkitTask started = PluginInitiationUtils.scheduleUnlessDisabling(
                        "UltiCloud telemetry", () -> Bukkit.getScheduler().runTaskTimer(plugin, task, 20L, 20L));
                return () -> {
                    if (started != null) {
                        started.cancel();
                    }
                };
            };
            bindings.power = BukkitPowerExecutor::create;
            bindings.actionLog = entry -> {
                try {
                    RemoteActionLog log = plugin.getRemoteActionLog();
                    if (log != null) {
                        log.record(entry);
                    }
                } catch (RuntimeException failure) {
                    PanelConnectionLog.log(Level.WARNING, "UltiCloud: a remote action could not be written to "
                            + "the action log (" + failure.getClass().getSimpleName() + ").");
                }
            };
            bindings.files = () -> plugin.getFileOperationManager() == null ? null
                    : RemoteRequestHandlers.files(plugin.getFileOperationManager(),
                    new File(System.getProperty("user.dir")), bindings.actionLog);
            bindings.properties = () -> plugin.getServerPropertiesManager() == null ? null
                    : RemoteRequestHandlers.properties(plugin.getServerPropertiesManager(), bindings.actionLog);
            bindings.modules = () -> {
                PluginManager modules = plugin.getPluginManager();
                return modules == null ? Collections.<UltiToolsPlugin>emptyList() : modules.getPluginList();
            };
            bindings.configs = plugin::getConfigManager;
            bindings.responders = plugin::getPanelResponderRegistry;
            bindings.moduleVersion = name -> {
                PluginManager modules = plugin.getPluginManager();
                if (modules != null) {
                    for (UltiToolsPlugin module : modules.getPluginList()) {
                        if (name.equals(module.getPluginName())) {
                            return module.getVersion();
                        }
                    }
                }
                return null;
            };
            return bindings;
        }

        private static boolean errorReportingEnabled() {
            try {
                UltiTools instance = UltiTools.getInstance();
                return instance == null || instance.getConfig().getBoolean(AgentCapabilities.ERROR_REPORTING_KEY, true);
            } catch (RuntimeException failure) {
                return true;
            }
        }
    }
}

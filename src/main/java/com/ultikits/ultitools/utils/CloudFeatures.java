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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.RemoteRequestHandlers;
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
 * calls {@link #register(AgentMessageRouter, CloudChannel, AgentProtocol)} once for it, which registers the request
 * handlers on that connection's router and points the lifetime objects at that connection.
 * <p>
 * <b>Threads.</b> The player-link handler of a module runs on one daemon thread, {@code UltiTools-CloudLinks}; the
 * create and handler deadlines run on a second, {@code UltiTools-CloudLinks-Timer}. Both stop in {@link #shutdown()}.
 * The router dispatches every other request on the agent thread, which is never the server thread: the file and
 * {@code server.properties} handlers do their blocking I/O there, and the handlers that call the Bukkit API hand that
 * work to the server thread through {@link Bindings#mainThread}.
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
        playerLinkChannel.onChannelClosed();
        handlerThread.shutdown();
        timers.shutdown();
    }

    /**
     * Registers the inbound handlers on one connection's router and points the lifetime objects at that connection.
     * Called by {@link CloudSession} once for each upgraded socket, before its {@code hello}.
     *
     * @param router   the connection's router
     * @param channel  the connection's live channel (open between welcome and close)
     * @param protocol the connection's protocol layer
     */
    void register(AgentMessageRouter router, CloudChannel channel, final AgentProtocol protocol) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(protocol, "protocol");
        this.protocol = protocol;
        registerCommands(router);
        registerPower(router);
        registerPlayerActions(router);
        registerAll(router, RemoteRequestHandlers.FILE_TYPES, bindings.files);
        registerAll(router, RemoteRequestHandlers.PROPERTY_TYPES, bindings.properties);
        registerConfig(router);
        registerModules(router, protocol);
        if (bindings.credentials != null) {
            router.register("credential.rotate", new CredentialRotationHandler(bindings.credentials));
        }
        playerLinkChannel.register(router);
        // The protocol layer consumes inbound error frames itself; the link channel hears them as reply listener.
        protocol.addReplyListener(playerLinkChannel::onErrorReply);
        protocol.addLifecycleListener(new AgentProtocol.Listener() {
            @Override
            public void closed() {
                if (CloudFeatures.this.protocol == protocol) {
                    playerLinkChannel.onChannelClosed();
                }
            }
        });
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
        JsonObject current = settings.helloSettings();
        settings.markReported(current);
        return current;
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
                                    Supplier<CloudRequestHandler> factory) {
        if (factory == null) {
            return;
        }
        CloudRequestHandler handler = factory.get();
        if (handler == null) {
            return;
        }
        for (String type : types) {
            router.register(type, handler);
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
        /** Persists a rotated credential (22-17); {@code null} leaves {@code credential.rotate} unregistered. */
        CredentialRotationHandler.CredentialPersister credentials = ServerCredentialStore::replace;
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

        /** @return bindings with no server behind them: only the switches, the credential store and links */
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

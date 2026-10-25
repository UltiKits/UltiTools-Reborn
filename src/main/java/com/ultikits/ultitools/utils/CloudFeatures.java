package com.ultikits.ultitools.utils;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;

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
 * The router dispatches every other request on the agent thread.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class CloudFeatures {

    /** The name of the thread the module's player-link handler runs on. */
    static final String HANDLER_THREAD_NAME = "UltiTools-CloudLinks";

    private static volatile CloudFeatures installed;

    private final Bindings bindings;
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

        /** @return bindings with no server behind them: links report offline mode */
        static Bindings forTesting() {
            return new Bindings();
        }

        /**
         * @param plugin the running framework plugin
         * @return every port bound to the running server
         */
        static Bindings forPlugin(UltiTools plugin) {
            Bindings bindings = new Bindings();
            bindings.onlineMode = () -> Bukkit.getServer() != null && Bukkit.getOnlineMode();
            return bindings;
        }
    }
}

package com.ultikits.ultitools.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * The UltiCloud protocol v2 layer of one agent connection attempt (as of 6.3.1): the versioned
 * handshake, the per-connection limits, the reliable-event resend and acknowledgement, the
 * overflow report, inbound control errors and the live {@link CloudChannel}.
 * <p>
 * <b>Handshake (D-12, D-13, contract SEMANTICS 10).</b> {@link #onOpen()} sends {@code hello}
 * as the connection's first frame; nothing else is sent until a {@code welcome} answers it. A
 * welcome counts only when it replies to that hello, matches the frozen schema and keeps every limit
 * inside the contract bounds (S-51); otherwise it is answered with {@code error invalid_message},
 * the socket is closed with 1000 and the normal backoff follows. A valid welcome naming another
 * server id than the linked one stops the connection for good, credential kept (contract answer 21,
 * SEMANTICS 16). Only a valid, matching welcome makes the connection established: the backoff
 * counters reset, the heartbeat starts ({@link AgentConnection#markEstablished()}) and
 * {@link #channel()} opens.
 * <p>
 * <b>Reliable events (D-11, D-15, contract answers 3 and 4).</b> After the welcome every
 * unacknowledged outbox entry is sent again with its own envelope id and timestamp, then a pending
 * drop count is reported once as {@code outbox.overflow} and cleared only when that frame was handed
 * to the socket. An {@code ack { ids }} removes exactly those ids from the outbox.
 * <p>
 * <b>Capabilities (contract SEMANTICS 8).</b> An outbound frame whose capability the welcome did
 * not accept is not sent; an inbound request whose capability the welcome did not accept, or whose
 * operator switch is off, is refused with {@code capability_not_accepted} by the
 * {@link AgentMessageRouter}. The welcome's accepted list is intersected with what the hello
 * declared, so a gateway cannot widen it.
 * <p>
 * <b>Inbound errors (SEMANTICS 6, 14).</b> An {@code error} with {@code replyTo} reaches every
 * {@linkplain #addReplyListener reply listener} and logs one WARNING per error code per minute; an
 * {@code error rate_limited} without {@code replyTo} is logged at FINE, at most once a minute at
 * INFO. An error is never answered. No log line carries a payload, a message from the gateway or
 * a credential (S-34).
 * <p>
 * <b>Threads and locks.</b> Inbound frames arrive on the agent scheduler thread. Every frame this
 * class hands to the socket, from any thread, is serialised on one private lock, which is held only
 * while the frame is handed over ({@link AgentConnection#send(String)} never blocks) and never while
 * calling a listener, the router or a connection method that calls out. In the session's lock order
 * this lock sits below the session monitor and above the {@link AgentConnection} monitor and the
 * {@link CloudOutbox} and {@link CommandDedupeSet} leaf monitors.
 */
final class AgentProtocol {

    /** The routing sentinel {@code module.request} carries instead of a capability name. */
    static final String MODULE_CAPABILITY_SENTINEL = "fromPayload:module";
    /** How often one notice of a kind may be logged above FINE. */
    static final long NOTICE_INTERVAL_MS = 60_000L;
    /** The schema bound on the number of capabilities in hello and welcome. */
    static final int MAX_CAPABILITIES = 64;
    /** The schema bound on the length of one capability name. */
    static final int MAX_CAPABILITY_CHARS = 96;
    /** The schema bound on the ids of one {@code ack}. */
    static final int MAX_ACK_IDS = 500;

    private static final long MAX_TIMESTAMP = 8640000000000000L;
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");
    private static final String[] WELCOME_KEYS = {"protocol", "serverId", "accepted", "limits", "serverTime"};

    /** Lifecycle callbacks of one connection; never invoked while this protocol's lock is held. */
    interface Listener {
        /** @param channel the live channel of the just-established connection */
        default void established(CloudChannel channel) {
        }

        /** The established connection closed; its channel no longer sends. */
        default void closed() {
        }
    }

    private final Object lock = new Object();
    private final AgentConnection connection;
    private final CloudOutbox outbox;
    private final CommandDedupeSet dedupe;
    private final String expectedServerId;
    private final Supplier<List<String>> capabilities;
    private final Supplier<JsonObject> settings;
    private final Predicate<String> capabilityEnabled;
    private final LongSupplier clock;
    private final BiConsumer<Level, String> log;
    private final AgentMessageRouter router;
    private final FramedChannel framed;
    private final CloudChannel channel = new LiveChannel();
    private final List<Consumer<CloudRequest>> replyListeners = new CopyOnWriteArrayList<>();
    private final List<Listener> lifecycleListeners = new CopyOnWriteArrayList<>();

    // Guarded by lock.
    private String helloId;
    private Set<String> declared = Collections.emptySet();
    private boolean welcomeSeen;
    private boolean closed;
    private final Map<String, Long> lastNotice = new HashMap<>();

    // Written under lock, read without it by the channel and the router's gate.
    private volatile boolean established;
    private volatile CloudLimits limits = CloudLimits.defaults();
    private volatile Set<String> accepted = Collections.emptySet();
    private volatile long gatewayClockOffsetMillis;

    /**
     * The production protocol of one connection attempt, logging through {@link PanelConnectionLog}.
     *
     * @param connection           the attempt's connection; frames are handed to its {@code send}
     * @param outbox               the session's reliable-event buffer, or {@code null} when it has none
     * @param dedupe               the session's command-id set, or {@code null} when it has none
     * @param expectedServerId     the linked server id the welcome must name
     * @param capabilities         the capabilities the hello declares
     * @param settings             the operator settings the hello reports ({@code powerActionsEnabled})
     * @param capabilityEnabled    the operator switch of a contract capability (S-66); also asked
     *                             for {@link #MODULE_CAPABILITY_SENTINEL}
     * @param refusalForCapability safe refusal text naming the switch's configuration key
     * @param clock                epoch milliseconds
     */
    AgentProtocol(AgentConnection connection, CloudOutbox outbox, CommandDedupeSet dedupe, String expectedServerId,
                  Supplier<List<String>> capabilities, Supplier<JsonObject> settings,
                  Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability,
                  LongSupplier clock) {
        this(connection, outbox, dedupe, expectedServerId, capabilities, settings, capabilityEnabled,
                refusalForCapability, clock, PanelConnectionLog::log);
    }

    /** The full constructor, with the log sink tests capture. */
    AgentProtocol(AgentConnection connection, CloudOutbox outbox, CommandDedupeSet dedupe, String expectedServerId,
                  Supplier<List<String>> capabilities, Supplier<JsonObject> settings,
                  Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability,
                  LongSupplier clock, BiConsumer<Level, String> log) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.outbox = outbox;
        this.dedupe = dedupe;
        this.expectedServerId = expectedServerId;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.capabilityEnabled = Objects.requireNonNull(capabilityEnabled, "capabilityEnabled");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
        this.router = new AgentMessageRouter(this::inboundAllowed,
                Objects.requireNonNull(refusalForCapability, "refusalForCapability"), clock);
        this.framed = new FramedChannel(this::handOff, this::isEstablished, this::limits, Ulid::next, clock);
    }

    // ------------------------------------------------------------------ ports

    /** @return the router of this connection; track handlers are registered on it (22-21) */
    AgentMessageRouter router() {
        return router;
    }

    /** @return the live channel: open only between a valid welcome and the close */
    CloudChannel channel() {
        return channel;
    }

    /** @return the welcome's {@code serverTime} minus the local time at its receipt; 0 before welcome */
    long gatewayClockOffsetMillis() {
        return gatewayClockOffsetMillis;
    }

    /** @return whether a valid, matching welcome answered the hello and the connection is not closed */
    boolean isEstablished() {
        return established;
    }

    /** @return the accepted limits, or the contract defaults before welcome */
    CloudLimits limits() {
        return limits;
    }

    /** @return the capabilities the welcome accepted (within those the hello declared); empty before */
    Set<String> acceptedCapabilities() {
        return accepted;
    }

    /**
     * @param listener receives every inbound {@code error} that carries a {@code replyTo}, on the
     *                 agent thread; it must not block
     */
    void addReplyListener(Consumer<CloudRequest> listener) {
        replyListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * @param listener receives this connection's establishment and close; a listener added after the
     *                 connection was established is told at once
     */
    void addLifecycleListener(Listener listener) {
        lifecycleListeners.add(Objects.requireNonNull(listener, "listener"));
        if (established) {
            safely(() -> listener.established(channel));
        }
    }

    // ------------------------------------------------------------------ connection events

    /** The upgrade was accepted: sends {@code hello}, the connection's first frame. Once only. */
    void onOpen() {
        JsonObject payload = new JsonObject();
        payload.addProperty("framework", connection.frameworkVersion());
        payload.addProperty("protocol", 2);
        Set<String> names = helloCapabilities();
        JsonArray array = new JsonArray();
        for (String name : names) {
            array.add(name);
        }
        payload.add("capabilities", array);
        payload.add("settings", helloSettings());
        synchronized (lock) {
            if (helloId != null || closed) {
                return;
            }
            helloId = Ulid.next();
            declared = Collections.unmodifiableSet(names);
            // The only frame sent before welcome; the channel stays closed until then.
            connection.send(new Envelope(helloId, "hello", clock.getAsLong(), null, payload).toJson());
        }
    }

    /**
     * One inbound text frame other than the heartbeat reply.
     *
     * @param text the frame, never logged
     */
    void onText(String text) {
        List<Runnable> after = new ArrayList<>();
        Envelope envelope;
        try {
            envelope = Envelope.parse(text, limits.maxFrameBytes());
        } catch (InvalidFrameException invalid) {
            if (established) {
                // The router answers the frame with the contract's correlated error.
                router.dispatch(text, channel);
            } else {
                log.accept(Level.FINE, "UltiCloud: discarded an invalid frame received before welcome.");
            }
            return;
        }
        switch (envelope.type) {
            case "welcome":
                onWelcome(envelope, after);
                break;
            case "ack":
                onAck(envelope);
                break;
            case "error":
                onError(envelope, after);
                break;
            default:
                if (established) {
                    router.dispatch(text, channel);
                } else {
                    log.accept(Level.FINE, "UltiCloud: discarded a frame received before welcome.");
                }
                break;
        }
        for (Runnable action : after) {
            safely(action);
        }
    }

    /** The connection closed: the channel stops sending and lifecycle listeners are told once. */
    void onClose() {
        boolean wasEstablished;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            wasEstablished = established;
            established = false;
        }
        if (wasEstablished) {
            for (Listener listener : lifecycleListeners) {
                safely(listener::closed);
            }
        }
    }

    /**
     * Sends one reliable event that was just added to the outbox, if the connection is established
     * and its capability accepted; otherwise it waits in the outbox for the next welcome.
     *
     * @param entry the new outbox entry, sent with its own envelope id
     * @return whether it was handed to the socket
     */
    boolean sendReliable(CloudOutbox.Entry entry) {
        synchronized (lock) {
            return established && !closed && outboundAllowed(entry.type()) && sendEntry(entry);
        }
    }

    // ------------------------------------------------------------------ welcome

    private void onWelcome(Envelope envelope, List<Runnable> after) {
        List<Listener> notify = null;
        synchronized (lock) {
            if (closed || welcomeSeen) {
                log.accept(Level.FINE, "UltiCloud: ignored a repeated welcome.");
                return;
            }
            welcomeSeen = true;
            Welcome welcome;
            try {
                welcome = Welcome.read(envelope, helloId);
            } catch (InvalidFrameException | IllegalArgumentException invalid) {
                sendErrorUnlocked(envelope.id, "invalid_message",
                        "The welcome does not answer the hello or breaks the contract's limits.");
                notice(Level.WARNING, "invalid-welcome",
                        "UltiCloud: refused the gateway's welcome (invalid_message); the connection is retried.");
                after.add(() -> connection.abandonAttempt("invalid welcome"));
                return;
            }
            if (expectedServerId != null && !expectedServerId.equals(welcome.serverId)) {
                after.add(connection::stopForServerIdMismatch);
                return;
            }
            limits = welcome.limits;
            Set<String> granted = new LinkedHashSet<>(welcome.accepted);
            granted.retainAll(declared);
            accepted = Collections.unmodifiableSet(granted);
            gatewayClockOffsetMillis = welcome.serverTime - clock.getAsLong();
            applyLimits(welcome.limits);
            established = true;
            connection.markEstablished();
            resendUnacked();
            reportOverflow();
            notify = new ArrayList<>(lifecycleListeners);
        }
        log.accept(Level.INFO, "UltiCloud: connected.");
        for (Listener listener : notify) {
            after.add(() -> listener.established(channel));
        }
    }

    /** Under the lock: the welcome's limits reach the outbox and the command-dedupe set (D-13). */
    private void applyLimits(CloudLimits accepted) {
        if (outbox != null) {
            outbox.setCapacity(accepted.outboxMax());
        }
        if (dedupe != null) {
            dedupe.setLimits(accepted.commandDedupeMax(), accepted.commandDedupeTtlSeconds() * 1000L);
        }
    }

    /** Under the lock: every unacknowledged entry, with its own id and timestamp (D-11). */
    private void resendUnacked() {
        if (outbox == null) {
            return;
        }
        for (CloudOutbox.Entry entry : outbox.unacked()) {
            if (outboundAllowed(entry.type()) && !sendEntry(entry)) {
                return;
            }
        }
    }

    /** Under the lock: one {@code outbox.overflow}, cleared only once handed to the socket (D-15). */
    private void reportOverflow() {
        if (outbox == null) {
            return;
        }
        long dropped = outbox.droppedSinceLastReport();
        if (dropped <= 0) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("dropped", dropped);
        String text = new Envelope(Ulid.next(), "outbox.overflow", clock.getAsLong(), null, payload).toJson();
        if (fits(text) && connection.send(text)) {
            outbox.markOverflowReported(dropped);
        }
    }

    /** Under the lock. */
    private boolean sendEntry(CloudOutbox.Entry entry) {
        String text = new Envelope(entry.id(), entry.type(), entry.ts(), null, entry.payload()).toJson();
        if (!fits(text)) {
            log.accept(Level.FINE, "UltiCloud: kept an outbox event exceeding maxFrameBytes unsent.");
            return true;
        }
        return connection.send(text);
    }

    private boolean fits(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length <= limits.maxFrameBytes();
    }

    // ------------------------------------------------------------------ ack and error

    private void onAck(Envelope envelope) {
        if (!established) {
            log.accept(Level.FINE, "UltiCloud: discarded an ack received before welcome.");
            return;
        }
        List<String> ids;
        try {
            ids = ackIds(envelope);
        } catch (InvalidFrameException invalid) {
            notice(Level.WARNING, "invalid-ack", "UltiCloud: answered an invalid ack with invalid_message.");
            channel.replyError(envelope.id, "error", "invalid_message", "Payload does not match the message schema.",
                    null);
            return;
        }
        if (outbox != null) {
            outbox.ack(ids);
        }
    }

    private static List<String> ackIds(Envelope envelope) {
        PayloadReader reader = new PayloadReader(envelope.id, envelope.payload);
        reader.keys(new String[]{"ids"});
        JsonArray array = reader.array("ids", 1, MAX_ACK_IDS);
        List<String> ids = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || !ContractPrimitives.ULID.matcher(element.getAsString()).matches()) {
                throw new InvalidFrameException("invalid_message", envelope.id, "Invalid ack id.");
            }
            ids.add(element.getAsString());
        }
        return ids;
    }

    private void onError(Envelope envelope, List<Runnable> after) {
        String code;
        try {
            PayloadReader reader = new PayloadReader(envelope.id, envelope.payload);
            reader.keys(new String[]{"code", "message"}, "details");
            code = reader.string("code", 1, 64, ERROR_CODE);
            reader.string("message", 1, 500, null);
        } catch (InvalidFrameException invalid) {
            // An error is never answered, not even an invalid one.
            log.accept(Level.FINE, "UltiCloud: discarded an invalid error frame.");
            return;
        }
        if (envelope.replyTo != null) {
            CloudRequest request = envelope.request();
            for (Consumer<CloudRequest> listener : replyListeners) {
                after.add(() -> listener.accept(request));
            }
            synchronized (lock) {
                notice(Level.WARNING, "reply:" + code, "UltiCloud: the gateway answered a frame with error " + code + ".");
            }
        } else if ("rate_limited".equals(code)) {
            synchronized (lock) {
                notice(Level.INFO, "rate_limited", "UltiCloud: the gateway is limiting this server's log lines "
                        + "(rate_limited); lines over the limit are dropped.");
            }
        } else {
            synchronized (lock) {
                notice(Level.WARNING, "notice:" + code, "UltiCloud: the gateway reported error " + code + ".");
            }
        }
    }

    /**
     * Under the lock: logs {@code message} at {@code level} at most once per {@link #NOTICE_INTERVAL_MS}
     * per key, otherwise at FINE.
     */
    private void notice(Level level, String key, String message) {
        long now = clock.getAsLong();
        Long last = lastNotice.get(key);
        if (last == null || now - last >= NOTICE_INTERVAL_MS) {
            lastNotice.put(key, now);
            log.accept(level, message);
        } else {
            log.accept(Level.FINE, message);
        }
    }

    /** Under the lock: an error frame sent even before welcome (the refused welcome's answer). */
    private void sendErrorUnlocked(String replyTo, String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        connection.send(new Envelope(Ulid.next(), "error", clock.getAsLong(), replyTo, payload).toJson());
    }

    // ------------------------------------------------------------------ gates and framing

    /** The router's gate: accepted at the handshake and switched on by the operator. */
    private boolean inboundAllowed(String capability) {
        if (MODULE_CAPABILITY_SENTINEL.equals(capability)) {
            return capabilityEnabled.test(capability);
        }
        return accepted.contains(capability) && capabilityEnabled.test(capability);
    }

    private boolean outboundAllowed(String type) {
        AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
        return spec != null && (spec.capability() == null || accepted.contains(spec.capability()));
    }

    /** The framed channel's socket hand-off: serialised, and only while established. */
    private boolean handOff(String text) {
        synchronized (lock) {
            return established && !closed && connection.send(text);
        }
    }

    private Set<String> helloCapabilities() {
        Set<String> names = new LinkedHashSet<>();
        List<String> supplied;
        try {
            supplied = capabilities.get();
        } catch (RuntimeException failure) {
            log.accept(Level.WARNING, "UltiCloud: the capability list could not be built ("
                    + failure.getClass().getSimpleName() + "); the hello declares none.");
            return names;
        }
        if (supplied == null) {
            return names;
        }
        for (String name : supplied) {
            if (names.size() >= MAX_CAPABILITIES) {
                log.accept(Level.WARNING, "UltiCloud: more than " + MAX_CAPABILITIES
                        + " capabilities; the hello declares the first " + MAX_CAPABILITIES + ".");
                break;
            }
            if (name != null && name.length() <= MAX_CAPABILITY_CHARS
                    && ContractPrimitives.CAPABILITY.matcher(name).matches()) {
                names.add(name);
            }
        }
        return names;
    }

    private JsonObject helloSettings() {
        JsonObject result = new JsonObject();
        boolean power = false;
        try {
            JsonObject supplied = settings.get();
            JsonElement value = supplied == null ? null : supplied.get("powerActionsEnabled");
            power = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                    && value.getAsBoolean();
        } catch (RuntimeException failure) {
            log.accept(Level.WARNING, "UltiCloud: the operator settings could not be read ("
                    + failure.getClass().getSimpleName() + "); the hello reports power actions as off.");
        }
        result.addProperty("powerActionsEnabled", power);
        return result;
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.accept(Level.WARNING, "UltiCloud: a protocol listener failed: " + failure.getClass().getName());
        }
    }

    /** One validated welcome. */
    private static final class Welcome {
        final String serverId;
        final List<String> accepted;
        final CloudLimits limits;
        final long serverTime;

        private Welcome(String serverId, List<String> accepted, CloudLimits limits, long serverTime) {
            this.serverId = serverId;
            this.accepted = accepted;
            this.limits = limits;
            this.serverTime = serverTime;
        }

        /**
         * @throws InvalidFrameException    if the payload breaks the welcome schema
         * @throws IllegalArgumentException if a limit is outside the contract bounds
         */
        static Welcome read(Envelope envelope, String helloId) {
            if (helloId == null || !helloId.equals(envelope.replyTo)) {
                throw new InvalidFrameException("invalid_message", envelope.id, "The welcome does not answer the hello.");
            }
            PayloadReader reader = new PayloadReader(envelope.id, envelope.payload);
            reader.keys(WELCOME_KEYS);
            reader.integer("protocol", 2, 2);
            String serverId = reader.string("serverId", 26, 26, ContractPrimitives.ULID);
            JsonArray array = reader.array("accepted", 0, MAX_CAPABILITIES);
            List<String> accepted = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                        || element.getAsString().length() > MAX_CAPABILITY_CHARS
                        || !ContractPrimitives.CAPABILITY.matcher(element.getAsString()).matches()) {
                    throw new InvalidFrameException("invalid_message", envelope.id, "Invalid accepted capability.");
                }
                accepted.add(element.getAsString());
            }
            CloudLimits limits = CloudLimits.fromWelcome(reader.object("limits"));
            long serverTime = reader.integer("serverTime", 0, MAX_TIMESTAMP);
            return new Welcome(serverId, accepted, limits, serverTime);
        }
    }

    /** The live channel: the framed channel, with the outbound capability gate in front. */
    private final class LiveChannel implements CloudChannel {

        private boolean blocked(String type) {
            AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
            return spec != null && spec.capability() != null && !accepted.contains(spec.capability());
        }

        @Override
        public boolean isOpen() {
            return established;
        }

        @Override
        public CloudLimits limits() {
            return limits;
        }

        @Override
        public boolean send(String type, JsonObject payload) {
            return !blocked(type) && framed.send(type, payload);
        }

        @Override
        public String sendTracked(String type, JsonObject payload) {
            return blocked(type) ? null : framed.sendTracked(type, payload);
        }

        @Override
        public boolean reply(String replyTo, String type, JsonObject payload) {
            return framed.reply(replyTo, type, payload);
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data) {
            return framed.replyOk(replyTo, type, data);
        }

        @Override
        public boolean replyOk(String replyTo, String type, JsonElement data, String messageOrNull) {
            return framed.replyOk(replyTo, type, data, messageOrNull);
        }

        @Override
        public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull,
                                    String messageOrNull) {
            return framed.replyPartial(replyTo, type, data, codeOrNull, messageOrNull);
        }

        @Override
        public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
            return framed.replyError(replyTo, type, code, message, detailsOrNull);
        }
    }
}

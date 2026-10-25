package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudPlayerLinks;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Carries the player-link protocol behind {@link CloudPlayerLinks}: it frames
 * {@code player.link.create}, correlates the answer by envelope id, delivers completions and
 * expiries to the single owning module, and records each completion result in the shared ledger
 * before answering the gateway.
 *
 * <p>Pending creates are keyed by the envelope id the channel reports for the frame it just sent.
 * A completion whose create is still pending without an answer waits for that answer so the module
 * sees the link before the completion; a completion that arrives after a restart, with no create
 * pending in this run, is delivered straight away.</p>
 *
 * <p>Order of a completion result: the result is recorded and persisted in the ledger under the
 * completion's envelope id before the {@code player.link.completed.result} frame is handed to the
 * socket. A redelivery of that id finds the recorded result and re-sends it without calling the
 * handler again. A handler that does not answer in time sends nothing, so the gateway redelivers.</p>
 *
 * <p>The result of a handler call is sent on the connection that is live when the handler finishes,
 * not on the one the completion arrived on: the module may work for seconds, long enough for the
 * socket to be replaced. If no connection is open at that moment, nothing is sent and the persisted
 * receipt answers the next redelivery.</p>
 */
final class PlayerLinkChannel implements CloudPlayerLinks.Transport {
    private static final Logger LOGGER = Logger.getLogger(PlayerLinkChannel.class.getName());

    /** How long the gateway may take to answer one create, or one handler to answer a completion. */
    static final long REQUEST_DEADLINE_MS = 30_000L;
    /** How long the handler may take before the framework stops waiting and sends no result. */
    static final long HANDLER_DEADLINE_MS = 30_000L;

    private static final Pattern LINK_URL = Pattern.compile(
            "^https:\\/\\/[^\\u0000- \\u007F/?#]{1,253}\\/link#ucl_[^\\u0000- \\u007F#]{1,200}$");
    private static final long MAX_CLOCK_VALUE = 8640000000000000L;

    private final Supplier<CloudChannel> channel;
    private final DedupeLedger ledger;
    private final BooleanSupplier onlineMode;
    private final LongSupplier gatewayClockOffsetMillis;
    private final ScheduledExecutorService timers;
    private final Executor handlerThread;

    private final Map<String, Pending> pendingCreates = new ConcurrentHashMap<>();
    private final Set<String> busyCompletions = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicReference<Owner> handler = new AtomicReference<>();

    /**
     * Binds the channel to one connection's suppliers and to the shared session ledger.
     * @param channel supplies the current connection, or null while none exists
     * @param ledger the shared session ledger and synchronization monitor
     * @param onlineMode reports this server's online-mode setting
     * @param gatewayClockOffsetMillis the gateway clock minus the framework clock, in milliseconds
     * @param timers schedules the create and handler deadlines
     * @param handlerThread runs the owning module's handler
     */
    PlayerLinkChannel(Supplier<CloudChannel> channel, DedupeLedger ledger, BooleanSupplier onlineMode,
                      LongSupplier gatewayClockOffsetMillis, ScheduledExecutorService timers, Executor handlerThread) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.onlineMode = Objects.requireNonNull(onlineMode, "onlineMode");
        this.gatewayClockOffsetMillis = Objects.requireNonNull(gatewayClockOffsetMillis, "gatewayClockOffsetMillis");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.handlerThread = Objects.requireNonNull(handlerThread, "handlerThread");
    }

    /**
     * Registers this channel's handlers for the three link messages and for correlated errors.
     * @param router the session's message router
     */
    void register(AgentMessageRouter router) {
        Objects.requireNonNull(router, "router");
        router.register("player.link.created", new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel connection) {
                onCreated(request, connection);
            }
        });
        router.register("player.link.completed", new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel connection) {
                onCompleted(request, connection);
            }
        });
        router.register("player.link.expired", new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel connection) {
                onExpired(request, connection);
            }
        });
        router.register("error", new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel connection) {
                onErrorReply(request);
            }
        });
    }

    @Override
    public CompletableFuture<CloudPlayerLinks.LinkCreation> create(UltiToolsPlugin requester, UUID playerUuid,
                                                                   String playerName, CloudPlayerLinks.GameAuth gameAuth,
                                                                   Set<CloudPlayerLinks.LinkProof> offeredProofs) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(gameAuth, "gameAuth");
        if (playerName == null || playerName.isEmpty()) {
            throw new IllegalArgumentException("playerName must not be empty");
        }
        if (offeredProofs == null || offeredProofs.isEmpty() || offeredProofs.size() > 3) {
            throw new IllegalArgumentException("offeredProofs must hold one to three proofs");
        }
        CloudChannel connection = channel.get();
        if (connection == null || !connection.isOpen()) {
            return notConnected();
        }
        JsonObject payload = createPayload(playerUuid, playerName, gameAuth, offeredProofs);
        String id = connection.sendTracked("player.link.create", payload);
        if (id == null) {
            return notConnected();
        }
        Pending pending = new Pending();
        pendingCreates.put(id, pending);
        pending.deadline = timers.schedule(deadlineOfCreate(id), REQUEST_DEADLINE_MS, TimeUnit.MILLISECONDS);
        return pending.future;
    }

    @Override
    public void setHandler(UltiToolsPlugin owner, CloudPlayerLinks.LinkEventHandler handler) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(handler, "handler");
        synchronized (this.handler) {
            Owner current = this.handler.get();
            if (current != null && current.owner != owner) {
                throw new IllegalStateException("Another module already owns the player-link handler.");
            }
            this.handler.set(new Owner(owner, handler));
        }
    }

    @Override
    public void removeHandler(UltiToolsPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (this.handler) {
            Owner current = this.handler.get();
            if (current != null && current.owner == owner) {
                this.handler.set(null);
            }
        }
    }

    /**
     * Completes every pending create as not connected when the connection closes; held completions
     * are dropped because the gateway redelivers them after the next welcome.
     */
    void onChannelClosed() {
        for (String id : new ArrayList<>(pendingCreates.keySet())) {
            Pending pending = pendingCreates.remove(id);
            if (pending == null) {
                continue;
            }
            cancel(pending);
            pending.future.complete(CloudPlayerLinks.LinkCreation.failed(
                    CloudPlayerLinks.Failure.NOT_CONNECTED, "This server is not connected to UltiCloud."));
            pending.drainHeld();
        }
    }

    private Runnable deadlineOfCreate(final String id) {
        return new Runnable() {
            @Override
            public void run() {
                expireCreate(id);
            }
        };
    }

    private void expireCreate(String id) {
        Pending pending = pendingCreates.remove(id);
        if (pending == null) {
            return;
        }
        pending.future.complete(CloudPlayerLinks.LinkCreation.failed(
                CloudPlayerLinks.Failure.TIMED_OUT, "UltiCloud did not answer the link request in time."));
        for (Held held : pending.drainHeld()) {
            deliver(held.completion, held.channel);
        }
    }

    private void onCreated(CloudRequest request, CloudChannel connection) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        String status = reader.string("status", 1, 16, null);
        CloudPlayerLinks.LinkCreation creation;
        if ("ok".equals(status)) {
            reader.keys(new String[] {"status", "data"}, "message");
            PayloadReader data = new PayloadReader(request.id(), reader.object("data"));
            data.keys(new String[] {"url", "expiresAt", "acceptedProofs"});
            String url = data.string("url", 1, 500, LINK_URL);
            long expiresAt = data.integer("expiresAt", 0, MAX_CLOCK_VALUE);
            List<CloudPlayerLinks.LinkProof> accepted = proofs(data.array("acceptedProofs", 1, 3));
            creation = CloudPlayerLinks.LinkCreation.created(request.replyTo(), url,
                    Instant.ofEpochMilli(expiresAt - gatewayClockOffsetMillis.getAsLong()), accepted);
        } else if ("error".equals(status)) {
            reader.keys(new String[] {"status", "code", "message"}, "details");
            String code = reader.enumValue("code", "rate_limited", "link_not_permitted", "internal_error");
            String message = reader.string("message", 1, 500, null);
            creation = CloudPlayerLinks.LinkCreation.failed(failure(code), message);
        } else {
            throw new InvalidFrameException("invalid_message", request.id(),
                    "Payload does not match the message schema.");
        }
        Pending pending = pendingCreates.remove(request.replyTo());
        if (pending == null) {
            LOGGER.fine("UltiCloud: ignored a link answer for a create this run is not tracking.");
            return;
        }
        cancel(pending);
        pending.future.complete(creation);
        for (Held held : pending.drainHeld()) {
            deliver(held.completion, held.channel);
        }
    }

    private void onCompleted(CloudRequest request, CloudChannel connection) {
        CloudPlayerLinks.LinkCompletion completion = completionOf(request);
        String id = request.id();
        JsonObject recorded;
        synchronized (ledger) {
            recorded = ledger.receiptFor(id);
        }
        if (recorded != null) {
            sendResult(connection, id, recorded);
            return;
        }
        Pending pending = pendingCreates.get(request.replyTo());
        if (pending != null) {
            if (!busyCompletions.add(id)) {
                return;
            }
            pending.hold(new Held(completion, connection));
            return;
        }
        if (!busyCompletions.add(id)) {
            return;
        }
        deliver(completion, connection);
    }

    private void deliver(final CloudPlayerLinks.LinkCompletion completion, final CloudChannel connection) {
        final String id = completion.completionId();
        Owner owner = handler.get();
        if (owner == null) {
            answer(connection, id, noHandlerResult(completion));
            busyCompletions.remove(id);
            return;
        }
        final CloudPlayerLinks.LinkEventHandler target = owner.handler;
        handlerThread.execute(new Runnable() {
            @Override
            public void run() {
                invokeHandler(target, completion, connection, id);
            }
        });
    }

    private void invokeHandler(CloudPlayerLinks.LinkEventHandler target, CloudPlayerLinks.LinkCompletion completion,
                               CloudChannel connection, final String id) {
        CompletableFuture<CloudPlayerLinks.GameAccountResult> answer;
        try {
            answer = target.onCompleted(completion);
        } catch (RuntimeException ex) {
            LOGGER.warning("UltiCloud: the player-link handler failed for completion " + id
                    + "; no result is sent and the gateway will redeliver.");
            busyCompletions.remove(id);
            return;
        }
        if (answer == null) {
            LOGGER.warning("UltiCloud: the player-link handler returned no future for completion " + id
                    + "; no result is sent and the gateway will redeliver.");
            busyCompletions.remove(id);
            return;
        }
        final AtomicBoolean settled = new AtomicBoolean();
        final ScheduledFuture<?> deadline = timers.schedule(new Runnable() {
            @Override
            public void run() {
                if (settled.compareAndSet(false, true)) {
                    busyCompletions.remove(id);
                    LOGGER.warning("UltiCloud: the player-link handler did not answer completion " + id
                            + " in time; no result is sent and the gateway will redeliver.");
                }
            }
        }, HANDLER_DEADLINE_MS, TimeUnit.MILLISECONDS);
        answer.whenComplete(new java.util.function.BiConsumer<CloudPlayerLinks.GameAccountResult, Throwable>() {
            @Override
            public void accept(CloudPlayerLinks.GameAccountResult result, Throwable error) {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                deadline.cancel(false);
                if (error != null || result == null) {
                    busyCompletions.remove(id);
                    LOGGER.warning("UltiCloud: the player-link handler failed for completion " + id
                            + "; no result is sent and the gateway will redeliver.");
                    return;
                }
                try {
                    CloudChannel live = channel.get();
                    answer(live != null && live.isOpen() ? live : connection, id, resultOf(result));
                } finally {
                    busyCompletions.remove(id);
                }
            }
        });
    }

    private void onExpired(CloudRequest request, CloudChannel connection) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        reader.keys(new String[] {"player", "reason"});
        Player player = playerOf(reader.object("player"), request.id());
        String reason = reader.enumValue("reason", "expired", "cancelled", "denied");
        boolean first;
        synchronized (ledger) {
            first = ledger.firstSeen(request.id());
            if (first) {
                try {
                    ledger.persistNow();
                } catch (IOException ex) {
                    LOGGER.warning("UltiCloud: could not persist the link expiry id " + request.id()
                            + "; continuing with in-memory deduplication.");
                }
            }
        }
        if (!first) {
            return;
        }
        Owner owner = handler.get();
        if (owner == null) {
            return;
        }
        final CloudPlayerLinks.LinkExpiry expiry = new CloudPlayerLinks.LinkExpiry(request.replyTo(),
                player.uuid, player.name, expiryReason(reason));
        final CloudPlayerLinks.LinkEventHandler target = owner.handler;
        handlerThread.execute(new Runnable() {
            @Override
            public void run() {
                target.onExpired(expiry);
            }
        });
    }

    /**
     * Answers a correlated {@code error} reply for a create this run issued.
     * @param request the parsed error frame
     */
    void onErrorReply(CloudRequest request) {
        Pending pending = request.replyTo() == null ? null : pendingCreates.remove(request.replyTo());
        if (pending == null) {
            return;
        }
        cancel(pending);
        String message = "UltiCloud could not process the link request.";
        try {
            PayloadReader reader = new PayloadReader(request.id(), request.payload());
            reader.keys(new String[] {"code", "message"}, "details");
            reader.string("code", 1, 64, null);
            message = reader.string("message", 1, 500, null);
        } catch (InvalidFrameException ex) {
            LOGGER.fine("UltiCloud: an error reply for a link create had an unexpected payload.");
        }
        pending.future.complete(CloudPlayerLinks.LinkCreation.failed(CloudPlayerLinks.Failure.INTERNAL_ERROR, message));
        pending.drainHeld();
    }

    private void answer(CloudChannel connection, String id, JsonObject result) {
        synchronized (ledger) {
            ledger.recordReceipt(id, result);
            try {
                ledger.persistNow();
            } catch (IOException ex) {
                LOGGER.warning("UltiCloud: could not persist the player-link result " + id
                        + "; a restart may ask the module again.");
            }
        }
        sendResult(connection, id, result);
    }

    private void sendResult(CloudChannel connection, String id, JsonObject result) {
        JsonElement status = result.get("status");
        if (status != null && "ok".equals(status.getAsString())) {
            connection.replyOk(id, "player.link.completed.result", result.get("data"));
        } else if (result.has("code")) {
            connection.replyError(id, "player.link.completed.result", result.get("code").getAsString(),
                    result.get("message").getAsString(), null);
        }
    }

    private static CompletableFuture<CloudPlayerLinks.LinkCreation> notConnected() {
        return CompletableFuture.completedFuture(CloudPlayerLinks.LinkCreation.failed(
                CloudPlayerLinks.Failure.NOT_CONNECTED, "This server is not connected to UltiCloud."));
    }

    private JsonObject createPayload(UUID playerUuid, String playerName, CloudPlayerLinks.GameAuth gameAuth,
                                     Set<CloudPlayerLinks.LinkProof> offeredProofs) {
        JsonObject player = new JsonObject();
        player.addProperty("uuid", playerUuid.toString().toLowerCase(Locale.ROOT));
        player.addProperty("name", playerName);
        JsonObject auth = new JsonObject();
        if (gameAuth.isAuthPlugin()) {
            auth.addProperty("provider", "auth_plugin");
            auth.addProperty("authenticated", gameAuth.authenticated());
            auth.addProperty("accountExists", gameAuth.accountExists());
        } else {
            auth.addProperty("provider", "none");
        }
        JsonArray proofs = new JsonArray();
        for (CloudPlayerLinks.LinkProof proof : new LinkedHashSet<>(offeredProofs)) {
            proofs.add(wireOf(proof));
        }
        JsonObject payload = new JsonObject();
        payload.add("player", player);
        payload.addProperty("serverOnlineMode", onlineMode.getAsBoolean());
        payload.add("gameAuth", auth);
        payload.add("offeredProofs", proofs);
        return payload;
    }

    private CloudPlayerLinks.LinkCompletion completionOf(CloudRequest request) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload());
        String proofValue = reader.string("proof", 1, 32, null);
        CloudPlayerLinks.LinkProof proof;
        if ("in_game_session".equals(proofValue)) {
            proof = CloudPlayerLinks.LinkProof.IN_GAME_SESSION;
        } else if ("linked_web_account".equals(proofValue)) {
            proof = CloudPlayerLinks.LinkProof.LINKED_WEB_ACCOUNT;
        } else if ("web_registration".equals(proofValue)) {
            proof = CloudPlayerLinks.LinkProof.WEB_REGISTRATION;
        } else {
            throw new InvalidFrameException("invalid_message", request.id(),
                    "Payload does not match the message schema.");
        }
        Player player = playerOf(reader.object("player"), request.id());
        boolean provision = reader.bool("provisionGameAccount");
        String webUsername = null;
        if (proof == CloudPlayerLinks.LinkProof.WEB_REGISTRATION) {
            reader.keys(new String[] {"proof", "player", "webUsername", "provisionGameAccount"});
            webUsername = reader.string("webUsername", 1, 32, null);
            if (!provision) {
                throw new InvalidFrameException("invalid_message", request.id(),
                        "Payload does not match the message schema.");
            }
        } else {
            reader.keys(new String[] {"proof", "player", "provisionGameAccount"});
        }
        return new CloudPlayerLinks.LinkCompletion(request.id(), request.replyTo(), proof, player.uuid,
                player.name, provision, webUsername);
    }

    private static Player playerOf(JsonObject json, String id) {
        PayloadReader reader = new PayloadReader(id, json);
        reader.keys(new String[] {"uuid", "name"});
        String uuid = reader.string("uuid", 1, 36, ContractPrimitives.MC_UUID);
        String name = reader.string("name", 1, 32, ContractPrimitives.MC_NAME);
        return new Player(UUID.fromString(uuid), name);
    }

    private static List<CloudPlayerLinks.LinkProof> proofs(JsonArray array) {
        List<CloudPlayerLinks.LinkProof> proofs = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new InvalidFrameException("invalid_message", null, "Payload does not match the message schema.");
            }
            String value = element.getAsString();
            if ("in_game_session".equals(value)) {
                proofs.add(CloudPlayerLinks.LinkProof.IN_GAME_SESSION);
            } else if ("linked_web_account".equals(value)) {
                proofs.add(CloudPlayerLinks.LinkProof.LINKED_WEB_ACCOUNT);
            } else if ("web_registration".equals(value)) {
                proofs.add(CloudPlayerLinks.LinkProof.WEB_REGISTRATION);
            } else {
                throw new InvalidFrameException("invalid_message", null, "Payload does not match the message schema.");
            }
        }
        return proofs;
    }

    private static String wireOf(CloudPlayerLinks.LinkProof proof) {
        switch (proof) {
            case IN_GAME_SESSION:
                return "in_game_session";
            case LINKED_WEB_ACCOUNT:
                return "linked_web_account";
            case WEB_REGISTRATION:
                return "web_registration";
            default:
                throw new IllegalArgumentException("Unknown link proof");
        }
    }

    private static CloudPlayerLinks.Failure failure(String code) {
        if ("rate_limited".equals(code)) {
            return CloudPlayerLinks.Failure.RATE_LIMITED;
        }
        if ("link_not_permitted".equals(code)) {
            return CloudPlayerLinks.Failure.LINK_NOT_PERMITTED;
        }
        return CloudPlayerLinks.Failure.INTERNAL_ERROR;
    }

    private static CloudPlayerLinks.ExpiryReason expiryReason(String reason) {
        if ("cancelled".equals(reason)) {
            return CloudPlayerLinks.ExpiryReason.CANCELLED;
        }
        if ("denied".equals(reason)) {
            return CloudPlayerLinks.ExpiryReason.DENIED;
        }
        return CloudPlayerLinks.ExpiryReason.EXPIRED;
    }

    private static JsonObject noHandlerResult(CloudPlayerLinks.LinkCompletion completion) {
        if (completion.provisionGameAccount()) {
            return errorResult("auth_plugin_unavailable",
                    "No module can create a game account on this server.");
        }
        return okResult("not_requested");
    }

    private static JsonObject resultOf(CloudPlayerLinks.GameAccountResult result) {
        switch (result) {
            case CREATED:
                return okResult("created");
            case NOT_REQUESTED:
                return okResult("not_requested");
            case ACCOUNT_EXISTS:
                return errorResult("account_exists", "This player already has a game account.");
            case REGISTRATION_REJECTED:
                return errorResult("registration_rejected",
                        "This server's rules refused to create a game account for this player.");
            case AUTH_PLUGIN_UNAVAILABLE:
                return errorResult("auth_plugin_unavailable",
                        "No usable in-game login plugin is available on this server.");
            default:
                return errorResult("internal_error", "The game account could not be provisioned.");
        }
    }

    private static JsonObject okResult(String gameAccount) {
        JsonObject data = new JsonObject();
        data.addProperty("gameAccount", gameAccount);
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.add("data", data);
        return result;
    }

    private static JsonObject errorResult(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "error");
        result.addProperty("code", code);
        result.addProperty("message", message);
        return result;
    }

    private static void cancel(Pending pending) {
        ScheduledFuture<?> deadline = pending.deadline;
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    /** One create this run issued and has not answered yet. */
    private static final class Pending {
        private final CompletableFuture<CloudPlayerLinks.LinkCreation> future = new CompletableFuture<>();
        private final List<Held> held = new ArrayList<>();
        private volatile ScheduledFuture<?> deadline;

        private synchronized void hold(Held item) {
            held.add(item);
        }

        private synchronized List<Held> drainHeld() {
            List<Held> copy = new ArrayList<>(held);
            held.clear();
            return copy;
        }
    }

    /** A completion that arrived before its create was answered. */
    private static final class Held {
        private final CloudPlayerLinks.LinkCompletion completion;
        private final CloudChannel channel;

        private Held(CloudPlayerLinks.LinkCompletion completion, CloudChannel channel) {
            this.completion = completion;
            this.channel = channel;
        }
    }

    /** The owning module and its handler. */
    private static final class Owner {
        private final UltiToolsPlugin owner;
        private final CloudPlayerLinks.LinkEventHandler handler;

        private Owner(UltiToolsPlugin owner, CloudPlayerLinks.LinkEventHandler handler) {
            this.owner = owner;
            this.handler = handler;
        }
    }

    /** The player fields shared by completions and expiries. */
    private static final class Player {
        private final UUID uuid;
        private final String name;

        private Player(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}

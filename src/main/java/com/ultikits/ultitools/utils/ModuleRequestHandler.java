package com.ultikits.ultitools.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UltiToolsException;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.jetbrains.annotations.ApiStatus;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Bridges UltiCloud protocol v2 {@code module.request} to the released 6.3.0
 * {@link PanelResponderRegistry}; internal, not for module use.
 *
 * <p>One request names a module, an action, that action's parameters and the actor the gateway
 * asserts (D-25). The payload is read strictly against the frozen {@code module.request} schema, so
 * a missing, extra or wrongly typed field becomes a correlated {@code invalid_message} before any
 * module code runs.</p>
 *
 * <p>The payload's {@code module} is the module's UltiCloud identifier -- its identify-string,
 * lower-case ({@link ModuleIds}), never its {@code plugin.yml} name. The responder is the one {@link
 * PanelResponderRegistry#moduleRequestType(String, String)} names: registered under {@code
 * <module>.<action>} or, as in 6.3.0, under {@code <owner name lower-cased>.<action>}, and owned by the
 * module with that identifier. A request can never reach another module's responder, and one that
 * would is answered {@code module_not_found} without dispatching (T-22-99). The responder receives a fresh object holding a copy of
 * {@code params} plus the {@code actor} object exactly as received; the plugin neither derives nor
 * overrides the actor, which the authenticated gateway channel vouches for (T-22-98).</p>
 *
 * <p>This handler does not consult the capabilities the gateway accepted: the router admits
 * {@code module.request} through its {@code fromPayload:module} sentinel, and the only module check
 * here is the responder's owner. Whether a request for a module whose declared capability the
 * gateway did not accept is filtered is decided in 22-21.</p>
 *
 * <p>Each module-owned request is also republished on the module-facing event bus as one
 * {@link PanelMessageEvent}, built on the dispatching thread and delivered on the main thread, with
 * the fields the v1 inbound path gives it: the type the responder is registered under, the
 * responder's input and the request envelope as received. A request answered
 * {@code module_not_found} reached no module's responder and publishes nothing.</p>
 *
 * <p>Exactly one {@code module.response} is produced per request, on whichever thread settles the
 * registry's future, and the dispatching thread is never blocked:
 * <ul>
 *   <li>the responder's result becomes {@code status: ok} with that result as {@code data},
 *       whatever its shape: an object, an array, a primitive, or {@code null} when the responder's
 *       future completed with {@code null} or {@code JsonNull} ("answered, no content");</li>
 *   <li>no responder, or one owned by another module, becomes {@code module_not_found};</li>
 *   <li>the registry's deadline passing becomes {@code deadline_exceeded};</li>
 *   <li>every other failure — a responder that throws, completes exceptionally, returns no future
 *       ({@code null} instead of a future), or is refused because the registry's queue is full or the registry shut down — becomes
 *       {@code module_error} carrying only the root cause's message, cut to the schema's limit;</li>
 *   <li>a result that cannot be written as strict JSON — a {@code NaN} or infinite number, which
 *       Gson writes as a bare token no strict parser accepts — becomes {@code module_error} with a
 *       fixed message, because the gateway would drop such a frame and the panel would wait for
 *       its own deadline;</li>
 *   <li>a reply that cannot be built at all becomes one {@code internal_error}, so the single reply
 *       is never lost to a channel or serialiser failure inside the completion callback.</li>
 * </ul>
 * @since 6.3.1
 */
@ApiStatus.Internal
final class ModuleRequestHandler implements CloudRequestHandler {

    /** The contract's action pattern: lowercase letters, digits, dot, underscore and hyphen. */
    private static final Pattern ACTION = Pattern.compile("^[a-z0-9._-]{1,64}$");
    /** The contract's bound on a module identifier. */
    private static final int MODULE_IDENTIFIER_MAX_LENGTH = 64;
    /** The largest actor user id the contract accepts. */
    private static final long MAX_USER_ID = 9007199254740991L;
    /** The contract's bound on a response message. */
    private static final int MESSAGE_MAX_LENGTH = 500;
    private static final String RESPONSE_TYPE = "module.response";
    private static final String NO_RESPONDER_MESSAGE = "No module responder is registered for this request.";
    private static final String DEADLINE_MESSAGE = "The module responder did not complete in time.";
    private static final String NON_JSON_MESSAGE = "The module's result is not valid JSON (NaN or Infinity).";
    private static final String INTERNAL_ERROR_MESSAGE =
            "The plugin could not build a reply for this request.";

    /**
     * Shared Gson used only to hand a result to a non-lenient {@link JsonWriter}, which is the one
     * check that both walks the whole value and refuses a non-finite number: {@code
     * Gson#toJson(JsonElement)} always writes leniently, and Gson's strict <em>reader</em> accepts
     * a bare {@code NaN} as the string {@code "NaN"}, so serialising and reparsing would prove
     * nothing.
     */
    private static final Gson RESULT_GSON = new Gson();

    private final PanelResponderRegistry registry;
    private final ShutdownGate.Signal shutdown;

    /**
     * Creates the bridge over the registry the framework already owns, never refused for a shutdown.
     *
     * @param registry the registry holding module responders; never null
     */
    ModuleRequestHandler(PanelResponderRegistry registry) {
        this(registry, ShutdownGate.Signal.OPEN);
    }

    /**
     * @param registry the registry holding module responders; never null
     * @param shutdown the shutdown signal ({@link ShutdownGate}): a request is handed to its responder only with a
     *                 permit, which is returned when the responder's work ends (22-23 second verification), so
     *                 {@code UltiTools#onDisable} waits for a responder that started before it, bounded by
     *                 {@link PluginInitiationUtils#PANEL_CHANGE_DRAIN_MS}
     */
    ModuleRequestHandler(PanelResponderRegistry registry, ShutdownGate.Signal shutdown) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
    }

    /**
     * Validates one {@code module.request} and hands it to its owning module's responder.
     *
     * @param request the parsed request whose payload names module, action, params and actor
     * @param channel the reply channel; a reply may be dropped when the connection has closed
     */
    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        JsonObject payload = request.payload();
        PayloadReader reader = new PayloadReader(request.id(), payload)
                .keys(new String[] {"module", "action", "params", "actor"});
        String module = reader.string("module", 1, MODULE_IDENTIFIER_MAX_LENGTH,
                ContractPrimitives.MODULE_IDENTIFIER);
        String action = reader.string("action", 1, 64, ACTION);
        JsonObject params = reader.object("params");
        JsonObject actor = reader.object("actor");
        validateActor(request.id(), actor);

        JsonObject data = params.deepCopy();
        data.add("actor", actor.deepCopy());
        // The module is addressed by its identifier (its identify-string, lower-case), never by its
        // plugin.yml name; a responder registered under the 6.3.0 name form is found too (22-26 fix).
        String messageType = registry.moduleRequestType(module, action);
        if (messageType == null) {
            channel.replyError(request.id(), RESPONSE_TYPE, "module_not_found", NO_RESPONDER_MESSAGE, null);
            return;
        }
        // The router's permit covers only this call; the responder runs on the registry's worker, so it takes its own.
        if (!shutdown.enter()) {
            channel.replyError(request.id(), RESPONSE_TYPE, "internal_error", ShutdownGate.MESSAGE, null);
            return;
        }
        final AtomicBoolean returned = new AtomicBoolean();
        Runnable exit = () -> {
            if (returned.compareAndSet(false, true)) {
                shutdown.exit();
            }
        };
        boolean handedOff = false;
        try {
            registry.dispatch(messageType, data, request.id(), exit)
                    .whenComplete((result, failure) -> reply(channel, request.id(), result, failure));
            handedOff = true;
        } finally {
            if (!handedOff) {
                exit.run();
            }
        }
        publishPanelMessageEvent(request, messageType, data);
    }

    /**
     * Republishes the request for modules that subscribe to {@link PanelMessageEvent}, exactly as
     * the v1 inbound path does: built here on the dispatching thread, delivered on the main thread,
     * carrying the type the responder is registered under, the responder's input (the request's
     * params plus the gateway-asserted actor) and the v2 envelope as received. Best effort: no
     * failure here may affect the request's single reply.
     */
    private static void publishPanelMessageEvent(CloudRequest request, String messageType, JsonObject data) {
        try {
            JsonObject envelope = new JsonObject();
            envelope.addProperty("v", 2);
            envelope.addProperty("id", request.id());
            envelope.addProperty("type", request.type());
            envelope.addProperty("ts", request.ts());
            envelope.add("payload", request.payload());
            PluginInitiationUtils.publishPanelMessageEvent(messageType, envelope, data);
        } catch (RuntimeException publishFailed) {
            PanelConnectionLog.log(Level.FINE,
                    "UltiCloud: could not publish a PanelMessageEvent for a module request.");
        }
    }

    /** Checks the gateway-asserted actor against the contract's actor object, exactly as sent. */
    private static void validateActor(String id, JsonObject actor) {
        PayloadReader reader = new PayloadReader(id, actor).keys(new String[] {"userId", "minecraftUuid"});
        reader.integer("userId", 1, MAX_USER_ID);
        JsonElement minecraftUuid = reader.required("minecraftUuid");
        if (minecraftUuid.isJsonNull()) {
            return;
        }
        if (!minecraftUuid.isJsonPrimitive() || !minecraftUuid.getAsJsonPrimitive().isString()
                || !ContractPrimitives.MC_UUID.matcher(minecraftUuid.getAsString()).matches()) {
            throw new InvalidFrameException("invalid_message", id, "Payload does not match the message schema.");
        }
    }

    /** Produces the request's single reply from the registry's outcome. */
    private static void reply(CloudChannel channel, String replyTo, JsonElement result, Throwable failure) {
        try {
            // The registry settles "no content" as JsonNull; a Java null is treated the same way.
            JsonElement data = result == null ? JsonNull.INSTANCE : result;
            if (failure == null && isStrictJson(data)) {
                channel.replyOk(replyTo, RESPONSE_TYPE, data);
            } else if (failure == null) {
                // The registry settled successfully, but what the module returned cannot be put in
                // a frame: Gson writes NaN and Infinity as bare tokens, no strict parser accepts
                // them, and the gateway would silently drop the frame the panel is waiting for.
                channel.replyError(replyTo, RESPONSE_TYPE, "module_error", NON_JSON_MESSAGE, null);
            } else if (isDeadline(failure)) {
                channel.replyError(replyTo, RESPONSE_TYPE, "deadline_exceeded", DEADLINE_MESSAGE, null);
            } else {
                channel.replyError(replyTo, RESPONSE_TYPE, "module_error", rootCauseMessage(failure), null);
            }
        } catch (RuntimeException replyFailed) {
            // The registry settles this future exactly once, so a reply that cannot be built would
            // otherwise be lost and the panel would wait for its own deadline: answer the request
            // once with the contract's own fallback code instead. The failure's text is not logged
            // or sent — it can quote module code or payload content.
            sendInternalError(channel, replyTo);
        }
    }

    /** Answers {@code internal_error} once, and gives up quietly if the channel refuses that too. */
    private static void sendInternalError(CloudChannel channel, String replyTo) {
        PanelConnectionLog.log(Level.WARNING,
                "UltiCloud: could not build a module.response for a module request; answering internal_error.");
        try {
            channel.replyError(replyTo, RESPONSE_TYPE, "internal_error", INTERNAL_ERROR_MESSAGE, null);
        } catch (RuntimeException channelRefused) {
            PanelConnectionLog.log(Level.FINE,
                    "UltiCloud: the connection refused the module.response for a module request.");
        }
    }

    /**
     * Whether {@code result} can be written as strict JSON. A non-lenient {@link JsonWriter}
     * refuses a non-finite {@code double} or {@code float} with {@link IllegalArgumentException},
     * which is exactly the value the gateway's parser rejects and Gson would otherwise write as the
     * bare token {@code NaN} or {@code Infinity}.
     */
    // PMD.AvoidCatchingGenericException: the Gson adapter reports an unserialisable value through
    // IllegalArgumentException; any runtime failure means "not strict JSON".
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static boolean isStrictJson(JsonElement result) {
        try (JsonWriter writer = new JsonWriter(new StringWriter())) {
            writer.setLenient(false);
            writer.setSerializeNulls(true);
            // Wrapped in an array: some Gson versions' strict writer refuses a top-level primitive
            // or null, and the server's bundled Gson may differ from the one the framework is tested with.
            JsonArray wrapped = new JsonArray();
            wrapped.add(result);
            RESULT_GSON.getAdapter(JsonElement.class).write(writer, wrapped);
            return true;
        } catch (IOException | RuntimeException notStrictJson) {
            return false;
        }
    }

    /** Whether the registry's own deadline failed this request, rather than a responder. */
    private static boolean isDeadline(Throwable failure) {
        return failure instanceof UltiToolsException
                && ((UltiToolsException) failure).getErrorCode() == ErrorCode.WEBSOCKET_RESPONDER_TIMEOUT;
    }

    /**
     * The deepest cause's message, which is the contract's {@code module_error} message; the
     * framework's own wrapper text around it is not sent. A blank message falls back to the root
     * cause's class name, and the result is cut to the schema's limit.
     */
    private static String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = root.getClass().getName();
        }
        return truncate(message);
    }

    private static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MESSAGE_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MESSAGE_MAX_LENGTH));
    }
}

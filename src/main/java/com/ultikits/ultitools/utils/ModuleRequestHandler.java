package com.ultikits.ultitools.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
import java.util.Locale;
import java.util.Objects;
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
 * <p>The responder is looked up by the type {@code <module>.<action>}, and only while the
 * responder's owner module name, lower-cased, equals the payload's {@code module}: a request can
 * never reach another module's responder, and one that would is answered {@code module_not_found}
 * without dispatching (T-22-99). The responder receives a fresh object holding a copy of
 * {@code params} plus the {@code actor} object exactly as received; the plugin neither derives nor
 * overrides the actor, which the authenticated gateway channel vouches for (T-22-98).</p>
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
 *   <li>the responder's result becomes {@code status: ok} with that result as {@code data};</li>
 *   <li>no responder, or one owned by another module, becomes {@code module_not_found};</li>
 *   <li>the registry's deadline passing becomes {@code deadline_exceeded};</li>
 *   <li>every other failure — a responder that throws, completes exceptionally, returns null, or is
 *       refused because the registry's queue is full or the registry shut down — becomes
 *       {@code module_error} carrying only the root cause's message, cut to the schema's limit;</li>
 *   <li>a result that cannot be written as strict JSON — a {@code NaN} or infinite number, which
 *       Gson writes as a bare token no strict parser accepts — becomes {@code module_error} with a
 *       fixed message, because the gateway would drop such a frame and the panel would wait for
 *       its own deadline;</li>
 *   <li>a reply that cannot be built at all becomes one {@code internal_error}, so the single reply
 *       is never lost to a channel or serialiser failure inside the completion callback.</li>
 * </ul>
 * <p>The contract also allows the {@code ok} shape with {@code data: null}. The 6.3.0 extension
 * point is typed {@code Function<JsonObject, CompletableFuture<JsonObject>>} and always treats a
 * null result as a failure, so that shape is not reachable through it without widening the
 * registry's released public signature.
 *
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

    /**
     * Creates the bridge over the registry the framework already owns.
     *
     * @param registry the registry holding module responders; never null
     */
    ModuleRequestHandler(PanelResponderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
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
        String messageType = module + "." + action;
        String owner = registry.ownerModuleOf(messageType);
        if (owner == null || !owner.toLowerCase(Locale.ROOT).equals(module)) {
            channel.replyError(request.id(), RESPONSE_TYPE, "module_not_found", NO_RESPONDER_MESSAGE, null);
            return;
        }
        registry.dispatch(messageType, data, request.id())
                .whenComplete((result, failure) -> reply(channel, request.id(), result, failure));
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
    private static void reply(CloudChannel channel, String replyTo, JsonObject result, Throwable failure) {
        try {
            if (failure == null && isStrictJson(result)) {
                channel.replyOk(replyTo, RESPONSE_TYPE, result);
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
        if (result == null) {
            return false;
        }
        try (JsonWriter writer = new JsonWriter(new StringWriter())) {
            writer.setLenient(false);
            writer.setSerializeNulls(true);
            RESULT_GSON.getAdapter(JsonElement.class).write(writer, result);
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

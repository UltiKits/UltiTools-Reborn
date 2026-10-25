package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UltiToolsException;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.Objects;
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
 * <p>Exactly one {@code module.response} is produced per request, on whichever thread settles the
 * registry's future, and the dispatching thread is never blocked:
 * <ul>
 *   <li>the responder's result becomes {@code status: ok} with that result as {@code data};</li>
 *   <li>no responder, or one owned by another module, becomes {@code module_not_found};</li>
 *   <li>the registry's deadline passing becomes {@code deadline_exceeded};</li>
 *   <li>every other failure — a responder that throws, completes exceptionally, returns null, or is
 *       refused because the registry's queue is full or the registry shut down — becomes
 *       {@code module_error} carrying only the root cause's message, cut to the schema's limit.</li>
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
        if (failure == null) {
            channel.replyOk(replyTo, RESPONSE_TYPE, result);
        } else if (isDeadline(failure)) {
            channel.replyError(replyTo, RESPONSE_TYPE, "deadline_exceeded", DEADLINE_MESSAGE, null);
        } else {
            channel.replyError(replyTo, RESPONSE_TYPE, "module_error", rootCauseMessage(failure), null);
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

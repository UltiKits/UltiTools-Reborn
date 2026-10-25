package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import org.jetbrains.annotations.ApiStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Validates envelopes and dispatches framework handlers; internal, not for module use.
 * Handlers validate their own payloads. Synchronous validation and runtime failures
 * become correlated errors; diagnostics never include raw payload or exception text.
 *
 * <p>The capability predicate receives contract capability names such as commands
 * and files:read, plus the literal {@code fromPayload:module} for module.request.
 * Integration must return true for that sentinel so the module handler can validate
 * the payload's module identifier and answer module_not_found when no responder is
 * owned by that module. The module handler does not check the negotiated
 * {@code modules:<id>@<version>} capabilities; whether a request for a module the
 * gateway did not accept is filtered is decided in 22-21. The sentinel is never
 * declared in hello or welcome. Likewise, accept power here; its handler applies the
 * operator switch and answers power_disabled itself.</p>
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class AgentMessageRouter {
    private static final Logger LOGGER = Logger.getLogger(AgentMessageRouter.class.getName());
    private final Map<String, CloudRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarnings = new HashMap<>();
    private final Predicate<String> capabilityEnabled;
    private final Function<String, String> refusalForCapability;
    private final LongSupplier clock;

    /**
     * Creates a router using the system clock for warning throttling.
     * @param capabilityEnabled the per-request capability gate
     * @param refusalForCapability supplies safe refusal text naming the configuration key
     */
    public AgentMessageRouter(Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability) {
        this(capabilityEnabled, refusalForCapability, System::currentTimeMillis);
    }

    /**
     * Creates a router with an injectable clock. Warnings are limited to one per
     * error code per minute; rejected requests still receive their own error reply.
     * @param capabilityEnabled the per-request capability gate
     * @param refusalForCapability supplies safe refusal text, never payload or exception text
     * @param clock supplies epoch milliseconds for warning throttling
     */
    public AgentMessageRouter(Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability, LongSupplier clock) {
        this.capabilityEnabled = Objects.requireNonNull(capabilityEnabled, "capabilityEnabled");
        this.refusalForCapability = Objects.requireNonNull(refusalForCapability, "refusalForCapability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers or replaces the handler for one frozen-contract message type.
     * @param type the exact registered protocol type
     * @param handler the non-null handler, invoked on the dispatching thread
     * @throws IllegalArgumentException if the type is not in the contract
     */
    public void register(String type, CloudRequestHandler handler) {
        if (AgentMessageTypes.spec(type) == null) {
            throw new IllegalArgumentException("Unregistered agent type");
        }
        handlers.put(type, Objects.requireNonNull(handler, "handler"));
    }

    /**
     * Parses and dispatches one frame, applying the capability gate before the handler.
     * Invalid frames without a usable id are discarded at FINE; otherwise failures
     * receive a correlated protocol error. Unhandled error, ack and welcome are ignored.
     * @param text the incoming JSON text, never included in diagnostics
     * @param channel supplies current limits and receives replies
     */
    public void dispatch(String text, CloudChannel channel) {
        Envelope envelope;
        try {
            envelope = Envelope.parse(text, channel.limits().maxFrameBytes());
        } catch (InvalidFrameException ex) {
            if (ex.replyTo() == null) {
                LOGGER.fine("UltiCloud: discarded a frame without a valid correlation id.");
            } else {
                error(channel, ex.replyTo(), ex.code(), "Invalid or unsupported agent frame.", null);
            }
            return;
        }
        try {
            dispatch(envelope, channel);
        } catch (InvalidFrameException ex) {
            error(channel, envelope.id, ex.code(), "Payload does not match the message schema.", null);
        } catch (RuntimeException ex) {
            error(channel, envelope.id, "internal_error", "The request could not be processed.", null,
                    "type=" + envelope.type + ", exception=" + ex.getClass().getName());
        }
    }

    private void dispatch(Envelope envelope, CloudChannel channel) {
        AgentMessageTypes.Spec spec = AgentMessageTypes.spec(envelope.type);
        String capability = spec.capability();
        if (spec.direction() == AgentMessageTypes.Direction.DOWN && capability != null
                && !capabilityEnabled.test(capability)) {
            refuse(channel, envelope.id, capability, refusalForCapability.apply(capability));
            return;
        }
        CloudRequestHandler handler = handlers.get(envelope.type);
        if (handler != null) {
            handler.handle(envelope.request(), channel);
        } else if ("error".equals(envelope.type) || "ack".equals(envelope.type) || "welcome".equals(envelope.type)) {
            LOGGER.fine("UltiCloud: ignored an unhandled control frame.");
        } else if (capability != null) {
            refuse(channel, envelope.id, capability, "This capability is not available on this server.");
        } else {
            error(channel, envelope.id, "unsupported_type", "No handler is registered for this message.", null);
        }
    }

    private void refuse(CloudChannel channel, String id, String capability, String message) {
        JsonObject details = new JsonObject();
        details.addProperty("capability", capability);
        error(channel, id, "capability_not_accepted", message, details);
    }

    private void error(CloudChannel channel, String id, String code, String message, JsonObject details) {
        error(channel, id, code, message, details, null);
    }

    private void error(CloudChannel channel, String id, String code, String message, JsonObject details, String diagnostic) {
        warn(code, diagnostic);
        channel.replyError(id, "error", code, message, details);
    }

    private synchronized void warn(String code, String diagnostic) {
        long now = clock.getAsLong();
        Long last = lastWarnings.get(code);
        if (last == null || now - last >= 60000L) {
            lastWarnings.put(code, now);
            LOGGER.warning("UltiCloud: agent request rejected (" + code + ")"
                    + (diagnostic == null ? "." : "; " + diagnostic + "."));
        }
    }
}

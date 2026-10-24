package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

final class AgentMessageRouter {
    private static final Logger LOGGER = Logger.getLogger(AgentMessageRouter.class.getName());
    private final Map<String, CloudRequestHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarnings = new HashMap<>();
    private final Predicate<String> capabilityEnabled;
    private final Function<String, String> refusalForCapability;
    private final LongSupplier clock;

    AgentMessageRouter(Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability) {
        this(capabilityEnabled, refusalForCapability, System::currentTimeMillis);
    }

    AgentMessageRouter(Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability, LongSupplier clock) {
        this.capabilityEnabled = Objects.requireNonNull(capabilityEnabled, "capabilityEnabled");
        this.refusalForCapability = Objects.requireNonNull(refusalForCapability, "refusalForCapability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void register(String type, CloudRequestHandler handler) {
        if (AgentMessageTypes.spec(type) == null) {
            throw new IllegalArgumentException("Unregistered agent type");
        }
        handlers.put(type, Objects.requireNonNull(handler, "handler"));
    }

    void dispatch(String text, CloudChannel channel) {
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
            error(channel, envelope.id, "internal_error", "The request could not be processed.", null);
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
        warn(code);
        channel.replyError(id, "error", code, message, details);
    }

    private synchronized void warn(String code) {
        long now = clock.getAsLong();
        Long last = lastWarnings.get(code);
        if (last == null || now - last >= 60000L) {
            lastWarnings.put(code, now);
            LOGGER.warning("UltiCloud: agent request rejected (" + code + ").");
        }
    }
}

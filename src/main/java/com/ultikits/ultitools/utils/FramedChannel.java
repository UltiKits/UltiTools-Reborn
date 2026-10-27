package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Frames protocol replies over a socket handoff, enforcing correlation and UTF-8
 * frame limits. Oversized replies send a small correlated error instead, while
 * returning false for the original reply. No payload validation is performed here.
 */
final class FramedChannel implements CloudChannel {
    private static final Logger LOGGER = Logger.getLogger(FramedChannel.class.getName());
    private final Predicate<String> socketSend;
    private final BooleanSupplier established;
    private final Supplier<CloudLimits> limits;
    private final Supplier<String> ids;
    private final LongSupplier clock;

    /**
     * Binds framing to one connection's lifecycle; suppliers must be safe on calling threads.
     * @param socketSend returns true only after handing the frame to the socket
     * @param established true only between accepted welcome and close
     * @param limits current accepted limits, or defaults before welcome
     * @param ids supplies a fresh valid ULID for each outgoing frame
     * @param clock supplies epoch milliseconds for outgoing timestamps
     */
    FramedChannel(Predicate<String> socketSend, BooleanSupplier established, Supplier<CloudLimits> limits,
                  Supplier<String> ids, LongSupplier clock) {
        this.socketSend = Objects.requireNonNull(socketSend, "socketSend");
        this.established = Objects.requireNonNull(established, "established");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public boolean isOpen() { return established.getAsBoolean(); }
    @Override public CloudLimits limits() { return limits.get(); }
    @Override public boolean send(String type, JsonObject payload) { return sendTracked(type, payload) != null; }

    @Override
    public String sendTracked(String type, JsonObject payload) {
        return frameTracked(null, type, payload, null);
    }

    @Override
    public String sendTracked(String type, JsonObject payload, Consumer<String> beforeHandOff) {
        return frameTracked(null, type, payload, Objects.requireNonNull(beforeHandOff, "beforeHandOff"));
    }

    @Override public boolean reply(String replyTo, String type, JsonObject payload) {
        return frameTracked(Objects.requireNonNull(replyTo, "replyTo"), type, payload, null) != null;
    }

    @Override
    public boolean replyOk(String replyTo, String type, JsonElement data) {
        return replyOk(replyTo, type, data, null);
    }

    @Override
    public boolean replyOk(String replyTo, String type, JsonElement data, String messageOrNull) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok");
        payload.add("data", data);
        if (messageOrNull != null) { payload.addProperty("message", messageOrNull); }
        return reply(replyTo, type, payload);
    }

    @Override
    public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull, String messageOrNull) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "partial");
        payload.add("data", data);
        if (codeOrNull != null) { payload.addProperty("code", codeOrNull); }
        if (messageOrNull != null) { payload.addProperty("message", messageOrNull); }
        return reply(replyTo, type, payload);
    }

    @Override
    public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
        JsonObject payload = new JsonObject();
        if (!"error".equals(type)) { payload.addProperty("status", "error"); }
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        if (detailsOrNull != null) { payload.add("details", detailsOrNull); }
        return reply(replyTo, type, payload);
    }

    private String frameTracked(String replyTo, String type, JsonObject payload, Consumer<String> beforeHandOff) {
        AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
        if (spec == null || (spec.replyTo() == AgentMessageTypes.ReplyTo.REQUIRED && replyTo == null)
                || (spec.replyTo() == AgentMessageTypes.ReplyTo.FORBIDDEN && replyTo != null)
                || (replyTo != null && !ContractPrimitives.ULID.matcher(replyTo).matches())) {
            throw new IllegalArgumentException("Invalid outgoing message type or replyTo");
        }
        if (!isOpen()) { return null; }
        String id = ids.get();
        Envelope envelope = new Envelope(id, type, clock.getAsLong(), replyTo, payload);
        String text = envelope.toJson();
        int maxBytes = limits().maxFrameBytes();
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            if (replyTo == null) {
                LOGGER.fine("UltiCloud: dropped an outgoing frame exceeding maxFrameBytes.");
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("code", "payload_too_large");
                error.addProperty("message", "The reply exceeds maxFrameBytes.");
                String replacement = new Envelope(ids.get(), "error", clock.getAsLong(), replyTo, error).toJson();
                if (isOpen() && replacement.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
                    socketSend.test(replacement);
                }
            }
            return null;
        }
        if (beforeHandOff != null) { beforeHandOff.accept(id); }
        if (!isOpen() || !socketSend.test(text)) { return null; }
        return id;
    }
}

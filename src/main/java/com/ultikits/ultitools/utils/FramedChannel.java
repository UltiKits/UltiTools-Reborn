package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class FramedChannel implements CloudChannel {
    private final Predicate<String> socketSend;
    private final BooleanSupplier established;
    private final Supplier<CloudLimits> limits;
    private final Supplier<String> ids;
    private final LongSupplier clock;

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
    @Override public boolean send(String type, JsonObject payload) { return frame(null, type, payload); }
    @Override public boolean reply(String replyTo, String type, JsonObject payload) {
        return frame(Objects.requireNonNull(replyTo, "replyTo"), type, payload);
    }

    @Override
    public boolean replyOk(String replyTo, String type, JsonElement data) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok");
        payload.add("data", data);
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

    private boolean frame(String replyTo, String type, JsonObject payload) {
        AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
        if (spec == null || (spec.replyTo() == AgentMessageTypes.ReplyTo.REQUIRED && replyTo == null)
                || (spec.replyTo() == AgentMessageTypes.ReplyTo.FORBIDDEN && replyTo != null)
                || (replyTo != null && !ContractPrimitives.ULID.matcher(replyTo).matches())) {
            throw new IllegalArgumentException("Invalid outgoing message type or replyTo");
        }
        if (!isOpen()) { return false; }
        Envelope envelope = new Envelope(ids.get(), type, clock.getAsLong(), replyTo, payload);
        return socketSend.test(envelope.toJson());
    }
}

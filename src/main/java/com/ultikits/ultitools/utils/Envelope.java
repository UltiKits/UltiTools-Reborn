package com.ultikits.ultitools.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.ultikits.ultitools.websocket.CloudRequest;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class Envelope {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("v", "id", "type", "ts", "replyTo", "payload"));
    final int v = 2;
    final String id;
    final String type;
    final long ts;
    final String replyTo;
    final JsonObject payload;

    Envelope(String id, String type, long ts, String replyTo, JsonObject payload) {
        this.id = id;
        this.type = type;
        this.ts = ts;
        this.replyTo = replyTo;
        this.payload = payload.deepCopy();
    }

    static Envelope parse(String text, int maxFrameBytes) {
        if (text != null && text.getBytes(StandardCharsets.UTF_8).length > maxFrameBytes) {
            throw new InvalidFrameException("payload_too_large", null, "Frame exceeds maxFrameBytes.");
        }
        JsonElement json;
        try (JsonReader reader = new JsonReader(new StringReader(text == null ? "" : text))) {
            reader.setLenient(false);
            json = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidFrameException("invalid_message", null, "Frame is not valid JSON.");
            }
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException ex) {
            throw new InvalidFrameException("invalid_message", null, "Frame is not valid JSON.");
        }
        if (json == null || !json.isJsonObject()) {
            throw new InvalidFrameException("invalid_message", null, "Frame is not an object.");
        }
        JsonObject object = json.getAsJsonObject();
        String id = string(object.get("id"));
        if (id == null || !ContractPrimitives.ULID.matcher(id).matches()) {
            id = null;
        }
        String type = string(object.get("type"));
        Long version = integer(object.get("v"), 2, 2);
        Long ts = integer(object.get("ts"), 0, 8640000000000000L);
        if (id == null || version == null || ts == null || type == null || type.length() > 64
                || !ContractPrimitives.TYPE_NAME.matcher(type).matches()) {
            throw new InvalidFrameException("invalid_message", id, "Invalid envelope head.");
        }
        AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
        if (spec == null) {
            throw new InvalidFrameException("unsupported_type", id, "Message type is not registered.");
        }
        String replyTo = string(object.get("replyTo"));
        boolean hasReply = object.has("replyTo");
        if ((hasReply && (replyTo == null || !ContractPrimitives.ULID.matcher(replyTo).matches()))
                || (spec.replyTo() == AgentMessageTypes.ReplyTo.REQUIRED && !hasReply)
                || (spec.replyTo() == AgentMessageTypes.ReplyTo.FORBIDDEN && hasReply)
                || !KEYS.containsAll(object.keySet()) || !object.has("payload") || !object.get("payload").isJsonObject()) {
            throw new InvalidFrameException("invalid_message", id, "Invalid envelope fields.");
        }
        return new Envelope(id, type, ts, replyTo, object.getAsJsonObject("payload"));
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static Long integer(JsonElement value, long min, long max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            long result = number.longValueExact();
            return result >= min && result <= max ? result : null;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    CloudRequest request() {
        return new CloudRequest(id, type, ts, replyTo, payload);
    }

    String toJson() {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", v);
        frame.addProperty("id", id);
        frame.addProperty("type", type);
        frame.addProperty("ts", ts);
        if (replyTo != null) {
            frame.addProperty("replyTo", replyTo);
        }
        frame.add("payload", payload);
        return GSON.toJson(frame);
    }
}

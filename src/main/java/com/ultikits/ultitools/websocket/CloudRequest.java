package com.ultikits.ultitools.websocket;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import java.util.Objects;

/** @since 6.3.1 */
@ApiStatus.Internal
public final class CloudRequest {
    private final String id;
    private final String type;
    private final long ts;
    private final String replyTo;
    private final JsonObject payload;

    public CloudRequest(String id, String type, long ts, String replyTo, JsonObject payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.ts = ts;
        this.replyTo = replyTo;
        this.payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    public String id() { return id; }
    public String type() { return type; }
    public long ts() { return ts; }
    public String replyTo() { return replyTo; }
    public JsonObject payload() { return payload.deepCopy(); }

    @Override
    public String toString() {
        return "CloudRequest{id=" + id + ", type=" + type + "}";
    }
}

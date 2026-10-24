package com.ultikits.ultitools.websocket;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;
import java.util.Objects;

/**
 * Immutable protocol request snapshot; internal, not for module use.
 * Payloads are copied on construction and access because callers must not mutate
 * another handler's request. Payload validation remains the handler's responsibility.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class CloudRequest {
    private final String id;
    private final String type;
    private final long ts;
    private final String replyTo;
    private final JsonObject payload;

    /**
     * Copies an envelope already checked by the protocol parser.
     * @param id the message ULID
     * @param type the registered message type
     * @param ts the sender's epoch-millisecond timestamp
     * @param replyTo the correlated request ULID, or null when absent
     * @param payload the non-null payload to copy; it may contain credentials
     */
    public CloudRequest(String id, String type, long ts, String replyTo, JsonObject payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.ts = ts;
        this.replyTo = replyTo;
        this.payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    /** @return the message ULID, used to correlate replies */
    public String id() { return id; }
    /** @return the registered message type */
    public String type() { return type; }
    /** @return the sender's epoch-millisecond timestamp */
    public long ts() { return ts; }
    /** @return the correlated request ULID, or null when absent */
    public String replyTo() { return replyTo; }
    /** @return a defensive deep copy of the payload; never log its contents */
    public JsonObject payload() { return payload.deepCopy(); }

    /** @return only id and type, never payload or credential data */
    @Override
    public String toString() {
        return "CloudRequest{id=" + id + ", type=" + type + "}";
    }
}

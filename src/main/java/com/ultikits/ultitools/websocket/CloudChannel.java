package com.ultikits.ultitools.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

/**
 * Frames and sends UltiCloud protocol v2 messages; internal, not for module use.
 *
 * <p>Sending methods return true only when the requested frame was handed to the socket,
 * not when the gateway acknowledged it. False means the requested frame was not handed
 * off: the channel may be closed, the socket may refuse it, or the frame may be too large.
 * An oversized reply may instead send a correlated {@code error payload_too_large}
 * and still return false. Payloads must already match their message schemas.</p>
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public interface CloudChannel {
    /**
     * Reports protocol readiness, not merely whether the socket is connected.
     * @return true only after an accepted welcome and before the connection closes
     */
    boolean isOpen();

    /**
     * Returns the current connection's accepted limits, or defaults before welcome.
     * @return the immutable limits snapshot
     */
    CloudLimits limits();

    /**
     * Sends a message with a fresh id and timestamp, without a replyTo field.
     * @param type a registered message type that does not require replyTo
     * @param payload the schema-valid message payload
     * @return true if the requested frame was handed to the socket
     * @throws IllegalArgumentException if the type is unknown or requires replyTo
     */
    boolean send(String type, JsonObject payload);

    /**
     * Sends a message with a fresh id and timestamp, without a replyTo field, and reports
     * the envelope id it used so the caller can correlate a later reply to this frame.
     *
     * <p>The default implementation cannot report the id and refuses; channels used by the
     * framework implement it so the id and the send path cannot diverge from {@link #send}.</p>
     *
     * @param type a registered message type that does not require replyTo
     * @param payload the schema-valid message payload
     * @return the envelope id of the frame handed to the socket, or null when nothing was
     *         handed off, including a closed channel or an oversized frame
     * @throws IllegalArgumentException if the type is unknown or requires replyTo
     * @throws UnsupportedOperationException if the channel cannot report the id it used
     * @since 6.3.1
     */
    default String sendTracked(String type, JsonObject payload) {
        throw new UnsupportedOperationException("This channel cannot report the envelope id it used.");
    }

    /**
     * Sends a correlated reply with a fresh id and timestamp.
     * @param replyTo the request's ULID, never null
     * @param type a registered message type that allows replyTo
     * @param payload the schema-valid reply payload
     * @return true if the requested reply was handed to the socket
     * @throws IllegalArgumentException if the type or correlation id is invalid
     */
    boolean reply(String replyTo, String type, JsonObject payload);

    /**
     * Replies with an OpOutcome payload containing {@code status: ok} and data.
     * @param replyTo the request's ULID
     * @param type the result message type
     * @param data result data matching that type's schema, including JSON null where allowed
     * @return true if the requested reply was handed to the socket
     */
    boolean replyOk(String replyTo, String type, JsonElement data);

    /**
     * Replies with {@code status: partial}; only result types declaring partial allow this.
     * @param replyTo the request's ULID
     * @param type the result message type
     * @param data partial result data matching that type's schema
     * @param codeOrNull an allowed result code, or null to omit it
     * @param messageOrNull a safe result message, or null to omit it
     * @return true if the requested reply was handed to the socket
     */
    boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull, String messageOrNull);

    /**
     * Replies with code, message and optional details. Result types include
     * {@code status: error}; the generic {@code error} type omits status.
     * @param replyTo the request's ULID
     * @param type the result message type or {@code error}
     * @param code an error code allowed for that type
     * @param message a safe diagnostic, never a credential or raw exception message
     * @param detailsOrNull schema-valid details, or null to omit them
     * @return true if the requested reply was handed to the socket
     */
    boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull);
}

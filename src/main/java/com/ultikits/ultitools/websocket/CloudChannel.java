package com.ultikits.ultitools.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

/** @since 6.3.1 */
@ApiStatus.Internal
public interface CloudChannel {
    boolean isOpen();
    CloudLimits limits();
    boolean send(String type, JsonObject payload);
    boolean reply(String replyTo, String type, JsonObject payload);
    boolean replyOk(String replyTo, String type, JsonElement data);
    boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull, String messageOrNull);
    boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull);
}

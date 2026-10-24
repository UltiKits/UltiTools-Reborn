package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;

/**
 * Handles one parsed UltiCloud request; internal, not for module use.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
@FunctionalInterface
public interface CloudRequestHandler {
    /**
     * Validates the payload and handles it on the dispatching thread. No Bukkit
     * main-thread scheduling is implied; handlers must schedule such work themselves.
     * Synchronous payload validation failures become correlated invalid_message replies;
     * other runtime failures become internal_error without exposing exception text.
     * Asynchronous failures are the handler's responsibility.
     * @param request the parsed envelope with a defensively copied payload
     * @param channel the reply channel, which may close during processing
     */
    void handle(CloudRequest request, CloudChannel channel);
}

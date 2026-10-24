package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;

/** @since 6.3.1 */
@ApiStatus.Internal
@FunctionalInterface
public interface CloudRequestHandler {
    void handle(CloudRequest request, CloudChannel channel);
}

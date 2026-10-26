package com.ultikits.ultitools.websocket;

/**
 * Strategy interface for WebSocket reconnection behavior.
 *
 * <p>
 * As of 6.3.1 nothing in the framework uses it: it described the protocol v1 reconnect loop.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 * @deprecated There is no replacement: the framework's own UltiCloud session (protocol 2.0.0)
 *     paces its reconnects itself. Removed in 6.4.0.
 * @removeIn 6.4.0
 */
@Deprecated(since = "6.3.1", forRemoval = true)
public interface ReconnectStrategy {
    
    /**
     * Gets the delay in milliseconds before the next reconnection attempt.
     *
     * @return the delay in milliseconds
     */
    long getNextDelay();

    /**
     * Resets the strategy state (called on successful connection).
     */
    void reset();

    /**
     * Gets the current attempt count.
     *
     * @return the number of attempts made
     */
    int getAttemptCount();

    /**
     * Checks if reconnection should continue.
     *
     * @return true if more reconnection attempts should be made
     */
    boolean shouldContinue();
}

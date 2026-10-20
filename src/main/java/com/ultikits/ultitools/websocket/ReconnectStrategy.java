package com.ultikits.ultitools.websocket;

/**
 * Strategy interface for WebSocket reconnection behavior.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
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

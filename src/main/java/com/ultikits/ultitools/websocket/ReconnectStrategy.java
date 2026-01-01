package com.ultikits.ultitools.websocket;

/**
 * Strategy interface for WebSocket reconnection behavior.
 * <p>
 * WebSocket 重连行为的策略接口。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public interface ReconnectStrategy {
    
    /**
     * Gets the delay in milliseconds before the next reconnection attempt.
     * 获取下次重连尝试前的延迟（毫秒）。
     *
     * @return the delay in milliseconds
     */
    long getNextDelay();
    
    /**
     * Resets the strategy state (called on successful connection).
     * 重置策略状态（在连接成功时调用）。
     */
    void reset();
    
    /**
     * Gets the current attempt count.
     * 获取当前尝试次数。
     *
     * @return the number of attempts made
     */
    int getAttemptCount();
    
    /**
     * Checks if reconnection should continue.
     * 检查是否应该继续重连。
     *
     * @return true if more reconnection attempts should be made
     */
    boolean shouldContinue();
}

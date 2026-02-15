package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.WebSocketMessageHandler;

/**
 * Handler for pong/heartbeat response messages.
 * <p>
 * 处理 pong/心跳响应消息的处理器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class PongHandler implements WebSocketMessageHandler {
    
    private static final Logger logger = Logger.getLogger(PongHandler.class.getName());
    
    private volatile long lastPongTime = 0;
    private volatile long latency = 0;
    private volatile long lastPingTime = 0;
    
    @Override
    public String getMessageType() {
        return "pong";
    }
    
    @Override
    public void handle(JsonObject message) {
        lastPongTime = System.currentTimeMillis();
        
        // Calculate latency if we have a ping timestamp
        if (lastPingTime > 0) {
            latency = lastPongTime - lastPingTime;
            logger.fine("Received pong, latency: " + latency + "ms");
        }
    }
    
    /**
     * Records when a ping was sent (for latency calculation).
     * 记录发送 ping 的时间（用于计算延迟）。
     */
    public void recordPing() {
        lastPingTime = System.currentTimeMillis();
    }
    
    /**
     * Gets the time of the last pong received.
     * 获取最后收到 pong 的时间。
     *
     * @return timestamp in milliseconds
     */
    public long getLastPongTime() {
        return lastPongTime;
    }
    
    /**
     * Gets the measured latency.
     * 获取测量的延迟。
     *
     * @return latency in milliseconds
     */
    public long getLatency() {
        return latency;
    }
    
    /**
     * Checks if the connection appears to be alive based on pong responses.
     * 根据 pong 响应检查连接是否存活。
     *
     * @param timeoutMs the timeout threshold in milliseconds
     * @return true if last pong was within the timeout
     */
    public boolean isAlive(long timeoutMs) {
        if (lastPongTime == 0) {
            return true; // No pong received yet, assume alive
        }
        return (System.currentTimeMillis() - lastPongTime) < timeoutMs;
    }
}

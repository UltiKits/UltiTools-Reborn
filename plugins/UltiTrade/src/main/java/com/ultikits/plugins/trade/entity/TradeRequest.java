package com.ultikits.plugins.trade.entity;

import java.util.UUID;

/**
 * Represents a trade request from one player to another.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TradeRequest {
    
    private final UUID sender;
    private final UUID receiver;
    private final long timestamp;
    
    public TradeRequest(UUID sender, UUID receiver) {
        this.sender = sender;
        this.receiver = receiver;
        this.timestamp = System.currentTimeMillis();
    }
    
    public UUID getSender() {
        return sender;
    }
    
    public UUID getReceiver() {
        return receiver;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public boolean isExpired(int timeoutSeconds) {
        return System.currentTimeMillis() - timestamp > timeoutSeconds * 1000L;
    }
}

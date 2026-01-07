package com.ultikits.plugins.social.entity;

import lombok.Data;
import lombok.AllArgsConstructor;

import java.util.UUID;

/**
 * Friend request entity.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@AllArgsConstructor
public class FriendRequest {
    
    private UUID sender;
    private String senderName;
    private UUID receiver;
    private long timestamp;
    
    /**
     * Check if request has expired.
     */
    public boolean isExpired(int timeoutSeconds) {
        return System.currentTimeMillis() - timestamp > timeoutSeconds * 1000L;
    }
    
    /**
     * Create a new friend request.
     */
    public static FriendRequest create(UUID sender, String senderName, UUID receiver) {
        return new FriendRequest(sender, senderName, receiver, System.currentTimeMillis());
    }
}

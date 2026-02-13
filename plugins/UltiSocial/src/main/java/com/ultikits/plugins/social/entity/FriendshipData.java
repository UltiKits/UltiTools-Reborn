package com.ultikits.plugins.social.entity;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.UUID;

/**
 * Friendship data entity.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("friendships")
public class FriendshipData extends BaseDataEntity<String> {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("friend_uuid")
    private String friendUuid;
    
    @Column("friend_name")
    private String friendName;
    
    @Column("created_time")
    private long createdTime;
    
    @Column("nickname")
    private String nickname;
    
    @Column("favorite")
    private boolean favorite;
    
    /**
     * Create a new friendship.
     */
    public static FriendshipData create(UUID playerUuid, UUID friendUuid, String friendName) {
        return FriendshipData.builder()
            .playerUuid(playerUuid.toString())
            .friendUuid(friendUuid.toString())
            .friendName(friendName)
            .createdTime(System.currentTimeMillis())
            .favorite(false)
            .build();
    }
}

package com.ultikits.plugins.remotebag.entity;

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
 * Remote bag data entity.
 * Stores serialized inventory contents for each player's bag pages.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("remote_bags")
public class RemoteBagData extends BaseDataEntity<Integer> {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("page_number")
    private int pageNumber;
    
    @Column(value = "contents", type = "TEXT")
    private String contents;
    
    @Column("last_updated")
    private long lastUpdated;
    
    /**
     * Create a new bag data entry.
     */
    public static RemoteBagData create(UUID playerUuid, int pageNumber, String contents) {
        return RemoteBagData.builder()
            .playerUuid(playerUuid.toString())
            .pageNumber(pageNumber)
            .contents(contents)
            .lastUpdated(System.currentTimeMillis())
            .build();
    }
}

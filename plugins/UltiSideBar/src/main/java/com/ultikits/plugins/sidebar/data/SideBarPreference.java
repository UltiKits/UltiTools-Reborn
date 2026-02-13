package com.ultikits.plugins.sidebar.data;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Data entity for storing player sidebar preferences.
 * <p>
 * Persists whether a player has enabled/disabled their sidebar display.
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("sidebar_preferences")
public class SideBarPreference extends BaseDataEntity<Long> {

    /**
     * Player UUID string.
     */
    @Column("player_uuid")
    private String playerUuid;

    /**
     * Whether the sidebar is enabled for this player.
     */
    @Column(value = "enabled", type = "BOOLEAN")
    private Boolean enabled;

    /**
     * Create a new preference for a player.
     *
     * @param playerUuid the player's UUID
     * @param enabled whether sidebar is enabled
     */
    public SideBarPreference(String playerUuid, Boolean enabled) {
        this.playerUuid = playerUuid;
        this.enabled = enabled;
    }
}

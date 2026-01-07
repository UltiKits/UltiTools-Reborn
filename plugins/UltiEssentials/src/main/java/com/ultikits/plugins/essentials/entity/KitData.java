package com.ultikits.plugins.essentials.entity;

import java.util.UUID;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Entity representing a kit.
 * <p>
 * 表示礼包的实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("essentials_kits")
public class KitData extends AbstractDataEntity {
    
    /**
     * Unique identifier for this kit.
     */
    @Column("uuid")
    private UUID uuid;
    
    /**
     * Name of the kit.
     */
    @Column("name")
    private String name;
    
    /**
     * Display name for the kit (can include color codes).
     */
    @Column("display_name")
    private String displayName;
    
    /**
     * Description of the kit.
     */
    @Column("description")
    private String description;
    
    /**
     * Serialized items in Base64 format.
     */
    @Column(value = "items", type = "TEXT")
    private String items;
    
    /**
     * Permission required to use this kit.
     */
    @Column("permission")
    private String permission;
    
    /**
     * Cooldown in seconds between uses.
     * -1 means one-time use only.
     */
    @Column("cooldown")
    private long cooldown;
    
    /**
     * Whether this kit is enabled.
     */
    @Column("enabled")
    private boolean enabled;
    
    /**
     * Display icon material name (for GUI).
     */
    @Column("icon")
    private String icon;
    
    /**
     * UUID of the creator.
     */
    @Column("created_by")
    private String createdBy;
    
    /**
     * Timestamp when the kit was created.
     */
    @Column("created_at")
    private long createdAt;
    
    /**
     * Checks if this is a one-time kit.
     */
    public boolean isOneTime() {
        return cooldown == -1;
    }
}

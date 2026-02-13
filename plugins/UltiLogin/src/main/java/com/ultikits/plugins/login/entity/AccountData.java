package com.ultikits.plugins.login.entity;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Player account data entity.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Table("login_accounts")
public class AccountData extends BaseDataEntity<Integer> {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("player_name")
    private String playerName;
    
    @Column(value = "password_hash", type = "VARCHAR(128)")
    private String passwordHash;
    
    @Column(value = "salt", type = "VARCHAR(32)")
    private String salt;
    
    @Column(value = "last_ip", type = "VARCHAR(45)")
    private String lastIp;
    
    @Column(value = "last_login", type = "BIGINT")
    private long lastLogin;
    
    @Column(value = "register_time", type = "BIGINT")
    private long registerTime;
    
    @Column(value = "register_ip", type = "VARCHAR(45)")
    private String registerIp;
    
    @Column(value = "email", type = "VARCHAR(100)")
    private String email;
    
    @Column(value = "email_verified", type = "BOOLEAN")
    private boolean emailVerified;
    
    @Column(value = "login_count", type = "INT")
    private int loginCount;
    
    @Column(value = "failed_attempts", type = "INT")
    private int failedAttempts;
    
    @Column(value = "last_failed_time", type = "BIGINT")
    private long lastFailedTime;
    
    public AccountData() {
        this.registerTime = System.currentTimeMillis();
        this.lastLogin = System.currentTimeMillis();
        this.loginCount = 0;
        this.failedAttempts = 0;
        this.emailVerified = false;
    }
}

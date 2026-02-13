package com.ultikits.plugins.social;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiSocial - Friend system module.
 * Provides friend management, online status, blacklist, and social features.
 *
 * Features:
 * - Friend management with bidirectional relationships
 * - Friend request system with auto-expiration
 * - Blacklist/block functionality
 * - Friend-to-friend teleportation with cooldown
 * - Private messaging between friends
 * - Rich GUI with pagination and status indicators
 * - Favorite friends feature
 *
 * Architecture:
 * - Uses UltiTools-API v6.2.0 Query DSL for database operations
 * - Scheduled task (@Scheduled) for automatic cleanup
 * - Config validation with @Range and @NotEmpty
 * - Service-oriented design with dependency injection
 *
 * @author wisdomme
 * @version 1.1.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.social"}
)
public class UltiSocial extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        getLogger().info("UltiSocial v1.1.0 has been enabled!");
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info("UltiSocial has been disabled!");
    }

    @Override
    public void reloadSelf() {
        getLogger().info("UltiSocial configuration reloaded!");
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}

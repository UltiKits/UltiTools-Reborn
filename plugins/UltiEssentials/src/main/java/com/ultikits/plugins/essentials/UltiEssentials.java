package com.ultikits.plugins.essentials;

import com.ultikits.plugins.essentials.service.*;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiEssentials - Essential commands and features for Minecraft servers.
 * <p>
 * This plugin provides common essential commands like teleportation, player status,
 * and server management features built on the UltiTools-API framework.
 * </p>
 *
 * @author wisdommen
 * @author UltiKits Team
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.essentials"})
public class UltiEssentials extends UltiToolsPlugin {

    private static UltiEssentials instance;

    /**
     * Gets the plugin instance.
     *
     * @return the UltiEssentials instance
     */
    public static UltiEssentials getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        // Initialize services
        initializeServices();
        
        getLogger().info(i18n("UltiEssentials 已启用！"));
        return true;
    }
    
    /**
     * Initializes all services that require data operators.
     */
    private void initializeServices() {
        // Initialize Home service
        HomeService homeService = getContext().getBean(HomeService.class);
        if (homeService != null) {
            homeService.init();
        }
        
        // Initialize TPA service
        TpaService tpaService = getContext().getBean(TpaService.class);
        if (tpaService != null) {
            tpaService.init();
        }
        
        // Initialize Warp service
        WarpService warpService = getContext().getBean(WarpService.class);
        if (warpService != null) {
            warpService.init();
        }
        
        // Initialize Ban service
        BanService banService = getContext().getBean(BanService.class);
        if (banService != null) {
            banService.init();
        }
        
        // Initialize Kit service
        KitService kitService = getContext().getBean(KitService.class);
        if (kitService != null) {
            kitService.init();
        }
        
        // Initialize Scoreboard service
        ScoreboardService scoreboardService = getContext().getBean(ScoreboardService.class);
        if (scoreboardService != null) {
            scoreboardService.init();
        }
        
        // Initialize Announcement service
        AnnouncementService announcementService = getContext().getBean(AnnouncementService.class);
        if (announcementService != null) {
            announcementService.init();
        }
        
        // Initialize ChestLock service
        ChestLockService chestLockService = getContext().getBean(ChestLockService.class);
        if (chestLockService != null) {
            chestLockService.init();
        }
        
        // Initialize NamePrefix service
        NamePrefixService namePrefixService = getContext().getBean(NamePrefixService.class);
        if (namePrefixService != null) {
            namePrefixService.init();
        }
    }

    @Override
    public void unregisterSelf() {
        getLogger().info(i18n("UltiEssentials 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiEssentials 配置已重载！"));
    }
}


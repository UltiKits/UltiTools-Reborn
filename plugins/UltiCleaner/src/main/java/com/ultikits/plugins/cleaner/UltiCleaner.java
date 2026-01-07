package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiCleaner - Automatic entity and item cleanup for Minecraft servers.
 * <p>
 * This plugin provides automatic cleanup of dropped items and entities
 * to improve server performance.
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.cleaner"})
public class UltiCleaner extends UltiToolsPlugin {

    private static UltiCleaner instance;

    public static UltiCleaner getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        // Initialize cleaner service
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.init();
        }
        
        getLogger().info(i18n("UltiCleaner 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        // Shutdown cleaner service
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.shutdown();
        }
        
        getLogger().info(i18n("UltiCleaner 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.reload();
        }
        getLogger().info(i18n("UltiCleaner 配置已重载！"));
    }
}

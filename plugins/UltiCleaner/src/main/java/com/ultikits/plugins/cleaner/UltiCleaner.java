package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.plugins.cleaner.utils.ServerTypeUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiCleaner - Advanced automatic entity and item cleanup for Minecraft servers.
 * <p>
 * Features:
 * - Automatic cleanup of dropped items and entities
 * - Smart cleanup based on entity count thresholds
 * - TPS-adaptive threshold adjustment
 * - Batch processing to minimize lag spikes
 * - Safe chunk unloading with Paper compatibility
 * - Custom events for extensibility
 * </p>
 *
 * @author wisdomme
 * @version 2.0.0
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
        
        // Log server type
        getLogger().info("Detected server: " + ServerTypeUtil.getServerSoftware());
        
        // Initialize cleaner service
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.init();
        }
        
        // Initialize chunk unload service
        ChunkUnloadService chunkUnloadService = getContext().getBean(ChunkUnloadService.class);
        if (chunkUnloadService != null) {
            chunkUnloadService.init();
        }
        
        getLogger().info(i18n("cleaner_enabled"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        // Shutdown cleaner service
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.shutdown();
        }
        
        // Shutdown chunk unload service
        ChunkUnloadService chunkUnloadService = getContext().getBean(ChunkUnloadService.class);
        if (chunkUnloadService != null) {
            chunkUnloadService.shutdown();
        }
        
        getLogger().info(i18n("cleaner_disabled"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.reload();
        }
        getLogger().info(i18n("cleaner_reloaded"));
    }
}

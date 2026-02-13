package com.ultikits.plugins.cleaner;

import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.plugins.cleaner.service.TpsAwareScheduler;
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

    @Override
    public boolean registerSelf() {
        // Log server type
        getLogger().info("Detected server: " + ServerTypeUtil.getServerSoftware());

        // Load configuration caches
        CleanerService cleanerService = getContext().getBean(CleanerService.class);
        if (cleanerService != null) {
            cleanerService.init();
        }

        // Initialize TPS scheduler
        TpsAwareScheduler tpsScheduler = getContext().getBean(TpsAwareScheduler.class);
        if (tpsScheduler != null) {
            tpsScheduler.init();
        }

        // Initialize chunk unload service logging
        ChunkUnloadService chunkUnloadService = getContext().getBean(ChunkUnloadService.class);
        if (chunkUnloadService != null) {
            chunkUnloadService.init();
        }

        getLogger().info(i18n("cleaner_enabled"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info(i18n("cleaner_disabled"));
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

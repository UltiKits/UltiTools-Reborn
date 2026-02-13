package com.ultikits.plugins.sidebar;

import com.ultikits.plugins.sidebar.service.SideBarService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiSideBar - Customizable sidebar scoreboard.
 * <p>
 * Features:
 * - Dynamic sidebar with PlaceholderAPI support
 * - Per-world configuration
 * - Toggle command with persistent preferences
 * - Auto-update with content caching
 * - Hot reload support
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.sidebar"})
public class UltiSideBar extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.init();
        }

        getLogger().info(i18n("sidebar_enabled"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.shutdown();
        }

        getLogger().info(i18n("sidebar_disabled"));
    }

    @Override
    public void reloadSelf() {
        // Reload all configs
        getConfigManager().reloadConfigs(this);

        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.reload();
        }
        getLogger().info(i18n("sidebar_reloaded"));
    }
}

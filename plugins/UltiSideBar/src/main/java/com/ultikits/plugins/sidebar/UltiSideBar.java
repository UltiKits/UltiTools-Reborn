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
 * - Toggle command
 * - Auto-update
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.sidebar"})
public class UltiSideBar extends UltiToolsPlugin {

    private static UltiSideBar instance;

    public static UltiSideBar getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.init();
        }
        
        getLogger().info(i18n("UltiSideBar 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.shutdown();
        }
        
        getLogger().info(i18n("UltiSideBar 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        SideBarService sideBarService = getContext().getBean(SideBarService.class);
        if (sideBarService != null) {
            sideBarService.reload();
        }
        getLogger().info(i18n("UltiSideBar 配置已重载！"));
    }
}

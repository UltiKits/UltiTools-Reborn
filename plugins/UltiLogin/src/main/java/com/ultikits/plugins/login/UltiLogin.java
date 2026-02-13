package com.ultikits.plugins.login;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiLogin - Player login and registration system.
 * <p>
 * Features:
 * - Player registration with password
 * - Login authentication
 * - Session persistence by IP
 * - Movement/action restriction before login
 * - Auto-kick on login timeout
 * </p>
 *
 * @author wisdomme
 * @version 1.0.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.login"})
public class UltiLogin extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        getLogger().info(i18n("UltiLogin 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info(i18n("UltiLogin 已禁用！"));
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiLogin 配置已重载！"));
    }
}

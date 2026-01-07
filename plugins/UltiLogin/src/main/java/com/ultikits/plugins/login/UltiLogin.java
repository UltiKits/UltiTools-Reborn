package com.ultikits.plugins.login;

import com.ultikits.plugins.login.service.LoginService;
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

    private static UltiLogin instance;

    public static UltiLogin getInstance() {
        return instance;
    }

    @Override
    public boolean registerSelf() {
        instance = this;
        
        LoginService loginService = getContext().getBean(LoginService.class);
        if (loginService != null) {
            loginService.init();
        }
        
        getLogger().info(i18n("UltiLogin 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        LoginService loginService = getContext().getBean(LoginService.class);
        if (loginService != null) {
            loginService.shutdown();
        }
        
        getLogger().info(i18n("UltiLogin 已禁用！"));
        instance = null;
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiLogin 配置已重载！"));
    }
}

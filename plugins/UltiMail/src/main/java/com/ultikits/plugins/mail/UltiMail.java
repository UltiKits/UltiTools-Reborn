package com.ultikits.plugins.mail;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * UltiMail - In-game mail system for Minecraft servers.
 * <p>
 * Features:
 * - Send mail to online/offline players
 * - Attach items to mail
 * - Mail notifications on join
 * - Mail history
 * </p>
 *
 * @author wisdomme
 * @version 1.1.0
 */
@UltiToolsModule(scanBasePackages = {"com.ultikits.plugins.mail"})
public class UltiMail extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        getLogger().info(i18n("UltiMail 已启用！"));
        return true;
    }

    @Override
    public void unregisterSelf() {
        getLogger().info(i18n("UltiMail 已禁用！"));
    }

    @Override
    public void reloadSelf() {
        getLogger().info(i18n("UltiMail 配置已重载！"));
    }
}

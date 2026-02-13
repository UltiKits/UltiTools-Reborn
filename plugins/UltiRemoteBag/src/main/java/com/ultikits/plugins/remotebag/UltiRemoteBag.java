package com.ultikits.plugins.remotebag;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

import java.util.Arrays;
import java.util.List;

/**
 * UltiRemoteBag - Virtual cloud storage module.
 * Provides remote bag (virtual chest) functionality for players.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@UltiToolsModule(
    scanBasePackages = {"com.ultikits.plugins.remotebag"}
)
public class UltiRemoteBag extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        // 初始化服务
        RemoteBagService bagService = getContext().getBean(RemoteBagService.class);
        if (bagService != null) {
            bagService.init();
        }

        // 设置锁超时时间
        BagLockService lockService = getContext().getBean(BagLockService.class);
        if (lockService != null) {
            RemoteBagConfig config = getContext().getBean(RemoteBagConfig.class);
            if (config != null) {
                lockService.setLockTimeout(config.getLockTimeout());
            }
        }

        getLogger().info("UltiRemoteBag has been enabled!");
        return true;
    }

    @Override
    public void unregisterSelf() {
        // 保存所有背包数据
        RemoteBagService bagService = getContext().getBean(RemoteBagService.class);
        if (bagService != null) {
            bagService.saveAllBags();
        }

        getLogger().info("UltiRemoteBag has been disabled!");
    }

    @Override
    public void reloadSelf() {
        getLogger().info("UltiRemoteBag configuration reloaded!");
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }
}

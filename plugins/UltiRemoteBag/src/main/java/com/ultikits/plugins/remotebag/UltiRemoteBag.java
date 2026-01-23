package com.ultikits.plugins.remotebag;

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
    
    private static UltiRemoteBag instance;
    
    @Override
    public boolean registerSelf() {
        instance = this;
        
        // 初始化服务
        RemoteBagService bagService = getContext().getBean(RemoteBagService.class);
        if (bagService != null) {
            bagService.init();
        }
        
        // 设置锁超时时间 - Bean initialization handled by framework
        // BagLockService is initialized via @Service annotation
        getContext().getBean(BagLockService.class);
        // 从配置获取超时时间（如果有的话）
        
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
    
    public static UltiRemoteBag getInstance() {
        return instance;
    }
}

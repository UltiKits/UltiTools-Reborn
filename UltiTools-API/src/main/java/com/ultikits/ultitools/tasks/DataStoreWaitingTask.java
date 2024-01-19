package com.ultikits.ultitools.tasks;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataStoreManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

public class DataStoreWaitingTask extends BukkitRunnable {
    private static int timer = 0;
    private final String storeType = UltiTools.getInstance().getConfig().getString("datasource.type");

    public DataStoreWaitingTask() {
        UltiTools.getInstance().getLogger().log(Level.INFO, "[UltiToolsAPI] 未能获取到配置的数据存储方式，正在重试！刚开服获取不到是正常的，数据存储模块加载需要时间。");
        UltiTools.getInstance().getLogger().log(Level.INFO, "[UltiToolsAPI] 尝试获取配置的数据存储方式...");
    }

    @Override
    public void run() {
        if (UltiTools.getInstance().getDataStore() != null
                && UltiTools.getInstance().getDataStore().getStoreType().equals(storeType)) {
            this.cancel();
            return;
        }
        if (timer == 120) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "[UltiToolsAPI] 仍未能获取到配置的数据存储方式！当前配置的存储方式为" + storeType + "。请检查是否安装相应的数据存储模块！已激活自带的Json本地文件存储。");
            this.cancel();
        }
        DataStore dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore != null && dataStore.getStoreType().equals(storeType)) {
            UltiTools.getInstance().getLogger().log(Level.INFO, "[UltiToolsAPI] 成功获取到数据存储！");
            UltiTools.getInstance().setDataStore(dataStore);
            this.cancel();
        }
        timer += 1;
    }
}

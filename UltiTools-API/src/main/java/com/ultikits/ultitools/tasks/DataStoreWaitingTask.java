package com.ultikits.ultitools.tasks;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.DataStoreManager;
import org.bukkit.scheduler.BukkitRunnable;

public class DataStoreWaitingTask extends BukkitRunnable {
    @Override
    public void run() {
        String storeType = UltiTools.getInstance().getConfig().getString("datasource.type");
        DataStore dataStore = DataStoreManager.getDatastore(storeType);
        System.out.println("尝试获取所需的数据存储...");
        if (dataStore != null) {
            System.out.println("成功获取到数据存储！");
            UltiTools.getInstance().setDataStore(dataStore);
            this.cancel();
        }
    }
}

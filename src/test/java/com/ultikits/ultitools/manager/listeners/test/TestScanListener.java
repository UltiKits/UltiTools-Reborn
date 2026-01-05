package com.ultikits.ultitools.manager.listeners.test;

import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 用于测试 ListenerManager.registerAll(plugin, packageName) 方法的测试监听器
 */
@EventListener
public class TestScanListener implements Listener {
    
    public TestScanListener() {
        // 默认构造函数，用于反射实例化
    }
    
    @org.bukkit.event.EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 测试用空实现
    }
}

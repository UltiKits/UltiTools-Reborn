package com.ultikits.plugins.remotebag.listener;

import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 远程背包事件监听器
 * <p>
 * 负责处理玩家退出时的清理工作：
 * <ul>
 *   <li>释放玩家持有的所有背包锁</li>
 *   <li>保存并清理缓存数据</li>
 * </ul>
 * <p>
 * 注意：GUI 交互事件由 mc.obliviate.inventory 框架处理，
 * 本监听器仅处理全局事件。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@EventListener
public class BagListener implements Listener {

    private final RemoteBagService bagService;
    private final BagLockService lockService;

    public BagListener(RemoteBagService bagService, BagLockService lockService) {
        this.bagService = bagService;
        this.lockService = lockService;
    }
    
    /**
     * 处理玩家退出事件
     * <p>
     * 当玩家退出时：
     * 1. 释放该玩家持有的所有背包锁
     * 2. 保存背包数据到数据库
     * 3. 清理内存缓存
     *
     * @param event 玩家退出事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // 释放该玩家持有的所有锁
        lockService.releaseAll(player.getUniqueId());
        
        // 保存并清理缓存
        bagService.saveBag(player.getUniqueId());
        bagService.clearCache(player.getUniqueId());
    }
}

package com.ultikits.plugins.chat.listener;

import com.ultikits.plugins.chat.config.ChannelConfig;
import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Manages player channel assignments on join/quit.
 * 管理玩家加入/退出时的频道分配。
 */
@EventListener
public class PlayerChannelListener implements Listener {

    @Autowired
    private ChannelService channelService;

    @Autowired
    private ChannelConfig channelConfig;

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        channelService.setPlayerChannel(
                event.getPlayer().getUniqueId(),
                channelConfig.getDefaultChannel()
        );
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        channelService.removePlayer(event.getPlayer().getUniqueId());
    }
}

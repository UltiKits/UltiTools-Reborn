package com.ultikits.plugins.essentials.listener;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.BanData;
import com.ultikits.plugins.essentials.service.BanService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.UUID;

/**
 * Listener for handling player ban checks on login.
 * <p>
 * 处理玩家登录时的封禁检查监听器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@EventListener
public class BanListener implements Listener {

    @Autowired
    private BanService banService;

    @Autowired
    private EssentialsConfig config;

    /**
     * Handles player login to check for bans.
     * Checks both UUID-based bans and IP-based bans.
     *
     * @param event the async player pre-login event
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(AsyncPlayerPreLoginEvent event) {
        if (!config.isBanEnabled()) {
            return;
        }

        UUID playerUuid = event.getUniqueId();
        String ipAddress = event.getAddress().getHostAddress();

        // Check UUID ban
        BanData activeBan = banService.getActiveBan(playerUuid);
        if (activeBan != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, 
                banService.formatKickMessage(activeBan));
            return;
        }

        // Check IP ban
        BanData ipBan = banService.getActiveIpBan(ipAddress);
        if (ipBan != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, 
                banService.formatKickMessage(ipBan));
        }
    }
}

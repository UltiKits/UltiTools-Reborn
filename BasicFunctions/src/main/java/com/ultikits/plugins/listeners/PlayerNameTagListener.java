package com.ultikits.plugins.listeners;

import com.nametagedit.plugin.NametagEdit;
import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.config.PlayerNameTagConfig;
import com.ultikits.plugins.utils.TitlesUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerNameTagListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerNameTagConfig nameTagConfig = BasicFunctions.getInstance().getConfig(PlayerNameTagConfig.class);
        String prefix = ChatColor.translateAlternateColorCodes('&', PlaceholderAPI.setPlaceholders(player, nameTagConfig.getPrefix()));
        String suffix = ChatColor.translateAlternateColorCodes('&', PlaceholderAPI.setPlaceholders(player, nameTagConfig.getSuffix()));
        if (nameTagConfig.isEnableNameTagEdit()) {
            NametagEdit.getApi().updatePlayerNametag(player.getName(), prefix, suffix);
        } else {
            TitlesUtils.setPrefixSuffix(player, prefix, suffix);
        }
    }
}

package com.ultikits.plugins.tasks;

import com.nametagedit.plugin.NametagEdit;
import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.config.PlayerNameTagConfig;
import com.ultikits.plugins.utils.TitlesUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class NamePrefixSuffixTask extends BukkitRunnable {

    @Override
    public void run() {
        PlayerNameTagConfig nameTagConfig = BasicFunctions.getInstance().getConfig(PlayerNameTagConfig.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            String prefix = ChatColor.translateAlternateColorCodes('&', PlaceholderAPI.setPlaceholders(player, nameTagConfig.getPrefix()));
            String suffix = ChatColor.translateAlternateColorCodes('&', PlaceholderAPI.setPlaceholders(player, nameTagConfig.getSuffix()));
            if (nameTagConfig.isEnableNameTagEdit()){
                NametagEdit.getApi().updatePlayerNametag(player.getName(), prefix, suffix);
            }else {
                TitlesUtils.setPrefixSuffix(player, prefix, suffix);
            }
        }
    }
}

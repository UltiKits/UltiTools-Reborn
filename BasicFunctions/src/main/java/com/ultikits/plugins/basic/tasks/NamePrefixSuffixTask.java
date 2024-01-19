package com.ultikits.plugins.basic.tasks;

import com.nametagedit.plugin.NametagEdit;
import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.PlayerNameTagConfig;
import com.ultikits.plugins.basic.utils.TitlesUtils;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

public class NamePrefixSuffixTask extends BukkitRunnable {

    @Override
    public void run() {
        PlayerNameTagConfig nameTagConfig = BasicFunctions.getInstance().getConfig(PlayerNameTagConfig.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            String prefix = coloredMsg(PlaceholderAPI.setPlaceholders(player, nameTagConfig.getPrefix()));
            String suffix = coloredMsg(PlaceholderAPI.setPlaceholders(player, nameTagConfig.getSuffix()));
            if (nameTagConfig.isEnableNameTagEdit()) {
                NametagEdit.getApi().updatePlayerNametag(player.getName(), prefix, suffix);
            } else {
                TitlesUtils.setPrefixSuffix(player, prefix, suffix);
            }
        }
    }
}

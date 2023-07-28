package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import fr.mrmicky.fastboard.FastBoard;
import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;

import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.*;

import java.io.IOException;
import java.util.*;

import static org.bukkit.Bukkit.getServer;

public class SidebarPlugin extends UltiToolsPlugin {
    @Getter
    private static SidebarPlugin instance;
    @Getter
    private final Map<UUID, FastBoard> boards = new HashMap<>();

    @Override
    public boolean registerSelf() throws IOException {
        instance = this;
        getConfigManager().register(this, new SidebarConfig("res/config/config.yml"));
        getListenerManager().register(this, new PlayerJoinListener());
        int updateInterval = getConfig(SidebarConfig.class).getUpdateInterval();
        new UpdateTask().runTaskTimerAsynchronously(UltiTools.getInstance(), 0, Math.max(updateInterval, 1));
        return true;
    }

    @Override
    public void unregisterSelf() {
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("zh", "en");
    }

    protected void updateBoard(FastBoard board) {
        List<String> list = new ArrayList<>();
        for (String s : getConfig(SidebarConfig.class).getContent()) {
            list.add(ChatColor.translateAlternateColorCodes('&', PlaceholderAPI.setPlaceholders(board.getPlayer(), s)));
        }
        board.updateLines(list);
    }
}

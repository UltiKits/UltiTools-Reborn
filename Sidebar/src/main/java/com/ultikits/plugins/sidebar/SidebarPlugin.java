package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import fr.mrmicky.fastboard.FastBoard;
import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;

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
        getListenerManager().register(this, new PlayerJoinListener());
        new UpdateTask().runTaskTimer(UltiTools.getInstance(), 0, 20);
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
        board.updateLines(
                "",
                "Players: " + getServer().getOnlinePlayers().size(),
                "",
                PlaceholderAPI.setPlaceholders(board.getPlayer(), "坐标: %player_x% &e%player_y% &e%player_z%"),
                "",
                PlaceholderAPI.setPlaceholders(board.getPlayer(), "%localtime_time%")
        );
    }
}

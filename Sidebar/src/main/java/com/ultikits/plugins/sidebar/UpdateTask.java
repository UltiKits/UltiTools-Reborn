package com.ultikits.plugins.sidebar;

import fr.mrmicky.fastboard.FastBoard;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

public class UpdateTask extends BukkitRunnable {
    @Override
    public void run() {
        for (FastBoard board : SidebarPlugin.getInstance().getBoards().values()) {
            if (board.isDeleted() || !board.getPlayer().isOnline()){
                continue;
            }
            List<String> list = new ArrayList<>();
            for (String s : SidebarPlugin.getInstance().getConfig(SidebarConfig.class).getContent()) {
                list.add(coloredMsg(PlaceholderAPI.setPlaceholders(board.getPlayer(), s)));
            }
            board.updateLines(list);
        }
    }
}

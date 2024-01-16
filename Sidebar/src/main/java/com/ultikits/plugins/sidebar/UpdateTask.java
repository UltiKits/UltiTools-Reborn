package com.ultikits.plugins.sidebar;

import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.scheduler.BukkitRunnable;

public class UpdateTask extends BukkitRunnable {
    @Override
    public void run() {
        for (FastBoard board : SidebarPlugin.getInstance().getBoards().values()) {
            if (board.isDeleted()){
                continue;
            }
            SidebarPlugin.getInstance().updateBoard(board);
        }
    }
}

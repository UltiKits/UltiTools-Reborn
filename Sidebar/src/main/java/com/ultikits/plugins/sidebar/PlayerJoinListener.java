package com.ultikits.plugins.sidebar;

import com.ultikits.ultitools.annotations.EventListener;
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@EventListener
public class PlayerJoinListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FastBoard board = new FastBoard(player);
        board.updateTitle(coloredMsg(SidebarPlugin.getInstance().getConfig(SidebarConfig.class).getTitle()));
        SidebarPlugin.getInstance().getBoards().put(player.getUniqueId(), board);
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        FastBoard board = SidebarPlugin.getInstance().getBoards().remove(player.getUniqueId());
        if (board != null) {
            board.delete();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        FastBoard board = SidebarPlugin.getInstance().getBoards().remove(player.getUniqueId());
        if (board != null) {
            board.delete();
        }
    }
}

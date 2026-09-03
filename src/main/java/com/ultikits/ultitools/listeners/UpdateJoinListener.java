package com.ultikits.ultitools.listeners;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.ultikits.ultitools.manager.UpdateManager;

/**
 * Notifies OP players about available updates when they join.
 * Each player is only notified once per server session.
 *
 * @since 6.2.0
 */
public class UpdateJoinListener implements Listener {

    private final UpdateManager updateManager;

    public UpdateJoinListener(UpdateManager updateManager) {
        this.updateManager = updateManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().isOp()) {
            return;
        }
        if (!updateManager.isCheckComplete()) {
            return;
        }
        if (!updateManager.hasAnyUpdates()) {
            return;
        }
        if (updateManager.isPlayerNotified(event.getPlayer().getUniqueId())) {
            return;
        }

        int count = updateManager.getModuleUpdates().size();
        if (updateManager.getFrameworkUpdate() != null) {
            count++;
        }

        event.getPlayer().sendMessage(
            ChatColor.GREEN + "[UltiTools] " + ChatColor.YELLOW
                + count + " update(s) available. Run /upm check for details."
        );
        updateManager.markPlayerNotified(event.getPlayer().getUniqueId());
    }
}

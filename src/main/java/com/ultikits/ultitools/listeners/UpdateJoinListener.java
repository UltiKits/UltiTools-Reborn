package com.ultikits.ultitools.listeners;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.manager.ManagerKeys;

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

        FrameworkMessages.messages().send(event.getPlayer(), ManagerKeys.UPDATE_JOIN_NOTICE, Arg.number("count", count));
        updateManager.markPlayerNotified(event.getPlayer().getUniqueId());
    }
}

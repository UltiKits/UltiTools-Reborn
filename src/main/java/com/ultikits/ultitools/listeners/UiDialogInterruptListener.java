package com.ultikits.ultitools.listeners;

import com.ultikits.ultitools.ui.dialog.DialogBackend;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.jetbrains.annotations.ApiStatus;

/** Observes game-driven Dialog dismissal without changing chest-session close behaviour. */
@ApiStatus.Internal
public final class UiDialogInterruptListener implements Listener {
    private final DialogBackend dialogs;

    public UiDialogInterruptListener(DialogBackend dialogs) {
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) { dialogs.interrupt(event.getEntity()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChangedWorld(PlayerChangedWorldEvent event) { dialogs.interrupt(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) { dialogs.interrupt(player); }
    }
}

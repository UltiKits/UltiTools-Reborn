package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.annotations.I18n;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command to teleport player back to their previous location.
 * Also acts as an event listener to track teleport locations.
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"back"}, permission = "ultiessentials.back", description = "返回上一个传送点")
@I18n("back.description")
public class BackCommand extends BaseEssentialsCommand implements Listener {

    /**
     * Temporarily stores player's last teleport location.
     * Data is lost on player disconnect or server restart (by design).
     */
    private static final Map<UUID, Location> LAST_LOCATIONS = new ConcurrentHashMap<>();

    private final EssentialsConfig config;

    public BackCommand(EssentialsConfig config) {
        this.config = config;
    }

    @CmdMapping(format = "")
    public void back(@CmdSender Player player) {
        if (!config.isBackEnabled()) {
            player.sendMessage(i18n("该功能已禁用"));
            return;
        }

        Location lastLocation = LAST_LOCATIONS.get(player.getUniqueId());
        if (lastLocation == null) {
            player.sendMessage(i18n("没有可返回的位置"));
            return;
        }

        player.teleport(lastLocation);
        player.sendMessage(i18n("已传送到上一个位置"));
    }

    /**
     * Listens for teleport events to record the previous location.
     *
     * @param event the teleport event
     */
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!config.isBackEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();

        // Only record command-triggered teleports
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            LAST_LOCATIONS.put(player.getUniqueId(), from);
        }
    }

    /**
     * Cleans up temporary data when player quits to prevent memory leaks.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        LAST_LOCATIONS.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Removes a player's stored location from the cache.
     *
     * @param uuid the player's UUID
     */
    public static void removePlayer(UUID uuid) {
        LAST_LOCATIONS.remove(uuid);
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("使用 /back 返回上一个传送点"));
    }
}

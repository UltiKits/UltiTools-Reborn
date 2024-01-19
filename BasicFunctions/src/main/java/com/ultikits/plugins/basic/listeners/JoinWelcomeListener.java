package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.JoinWelcomeConfig;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EventListener;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@EventListener(manualRegister = true)
public class JoinWelcomeListener implements Listener {

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.setJoinMessage(null);
        JoinWelcomeConfig config = BasicFunctions.getInstance().getConfig(JoinWelcomeConfig.class);
        if (player.isOp()) {
            Bukkit.broadcastMessage(coloredMsg(PlaceholderAPI.setPlaceholders(player, config.getOpJoin())));
        } else {
            Bukkit.broadcastMessage(coloredMsg(PlaceholderAPI.setPlaceholders(player, config.getPlayerJoin())));
        }

        new BukkitRunnable() {

            @Override
            public void run() {
                for (String each : config.getWelcomeMessage())
                    player.sendMessage(coloredMsg(PlaceholderAPI.setPlaceholders(player, each)));
            }

        }.runTaskLaterAsynchronously(UltiTools.getInstance(), config.getSendMessageDelay() * 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.setQuitMessage(null);
        JoinWelcomeConfig config = BasicFunctions.getInstance().getConfig(JoinWelcomeConfig.class);
        if (event.getPlayer().isOp()) {
            Bukkit.broadcastMessage(coloredMsg(PlaceholderAPI.setPlaceholders(player, config.getOpQuit())));
        } else {
            Bukkit.broadcastMessage(coloredMsg(PlaceholderAPI.setPlaceholders(player, config.getPlayerQuit())));
        }
    }
}

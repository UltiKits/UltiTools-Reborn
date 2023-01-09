package com.ultikits.plugins.joinwelcome.listener;

import com.ultikits.plugins.joinwelcome.JoinWelcome;
import com.ultikits.plugins.joinwelcome.config.Config;
import com.ultikits.ultitools.UltiTools;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class JoinListener implements Listener {

    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String vanillaJoinMessage = event.getJoinMessage() == null ? "" : event.getJoinMessage();
        event.setJoinMessage(null);
        Config config = JoinWelcome.getJoinWelcome().getConfig("res/config/config.yml", Config.class);
        if (player.isOp()) {
            Bukkit.broadcastMessage(PlaceholderAPI.setPlaceholders(player, config.getOpJoin() == null ? vanillaJoinMessage : config.getOpJoin().replace("%player_name%", player.getName())));
        } else {
            Bukkit.broadcastMessage(PlaceholderAPI.setPlaceholders(player, config.getPlayerJoin() == null ? vanillaJoinMessage : config.getPlayerJoin().replace("%player_name%", player.getName())));
        }

        new BukkitRunnable() {

            @Override
            public void run() {
                for (String each : config.getWelcomeMessage())
                    player.sendMessage(PlaceholderAPI.setPlaceholders(player, each.replaceAll("%player_name%", player.getName())));
            }

        }.runTaskLaterAsynchronously(UltiTools.getInstance(), config.getSendMessageDelay() * 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String vanillaQuitMessage = event.getQuitMessage() == null ? "" : event.getQuitMessage();
        event.setQuitMessage(null);
        Config config = JoinWelcome.getJoinWelcome().getConfig("res/config/config.yml", Config.class);
        if (event.getPlayer().isOp()) {
            Bukkit.broadcastMessage(PlaceholderAPI.setPlaceholders(player, config.getOpQuit() == null ? vanillaQuitMessage : config.getOpQuit().replace("%player_name%", player.getName())));
        } else {
            Bukkit.broadcastMessage(PlaceholderAPI.setPlaceholders(player, config.getPlayerQuit() == null ? vanillaQuitMessage : config.getPlayerQuit().replace("%player_name%", player.getName())));
        }
    }
}

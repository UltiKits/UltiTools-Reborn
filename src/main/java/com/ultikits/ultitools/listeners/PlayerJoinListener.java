package com.ultikits.ultitools.listeners;

import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EventListener;

import me.clip.placeholderapi.PlaceholderAPI;

@EventListener
public class PlayerJoinListener implements Listener {
    private final List<String> placeholderList = Arrays.asList("player", "server", "math", "vault", "localtime");

    // make the word first letter uppercase
    public static String toUpperCaseFirstOne(String s) {
        if (Character.isUpperCase(s.charAt(0)))
            return s;
        else
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Execute a command to download a PlaceholderAPI expansion.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param placeholder the placeholder expansion name to download
     */
    static void executeDownloadCommand(String placeholder) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi ecloud download " + toUpperCaseFirstOne(placeholder));
    }

    /**
     * Execute a command to reload PlaceholderAPI.
     * This method is extracted from BukkitRunnable for testability.
     */
    static void executeReloadCommand() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi reload");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            boolean reload = false;
            long time = 30L;
            for (String placeholder : placeholderList) {
                if (!PlaceholderAPI.isRegistered(placeholder)) {
                    reload = true;
                    final String ph = placeholder;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            executeDownloadCommand(ph);
                        }
                    }.runTaskLater(UltiTools.getInstance(), time);
                    time += 30L;
                }
            }
            if (reload) {
                final long finalTime = time;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        executeReloadCommand();
                    }
                }.runTaskLater(UltiTools.getInstance(), finalTime + 30L);
            }
        } catch (Exception ignored) {

        }
    }
}

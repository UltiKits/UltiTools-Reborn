package com.ultikits.ultitools.listeners;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.EventListener;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Arrays;
import java.util.List;

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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            boolean reload = false;
            long time = 30L;
            for (String placeholder : placeholderList) {
                if (!PlaceholderAPI.isRegistered(placeholder)) {
                    reload = true;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi ecloud download " + toUpperCaseFirstOne(placeholder));
                        }
                    }.runTaskLater(UltiTools.getInstance(), time);
                    time += 30L;
                }
            }
            if (reload) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi reload");
                    }
                }.runTaskLater(UltiTools.getInstance(), time + 30L);
            }
        } catch (Exception ignored) {

        }
    }
}

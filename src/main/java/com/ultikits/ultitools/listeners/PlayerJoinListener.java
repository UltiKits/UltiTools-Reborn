package com.ultikits.ultitools.listeners;

import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import com.ultikits.ultitools.scheduling.Ticks;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;

import com.ultikits.ultitools.annotations.EventListener;

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
     * This method is extracted from the scheduled task for testability.
     *
     * @param placeholder the placeholder expansion name to download
     */
    static void executeDownloadCommand(String placeholder) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi ecloud download " + toUpperCaseFirstOne(placeholder));
    }

    /**
     * Execute a command to reload PlaceholderAPI.
     * This method is extracted from the scheduled task for testability.
     */
    static void executeReloadCommand() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "papi reload");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            return;
        }
        try {
            boolean reload = false;
            long time = 30L;
            for (String placeholder : placeholderList) {
                if (!PlaceholderApiBridge.isRegistered(placeholder)) {
                    reload = true;
                    final String ph = placeholder;
                    FrameworkScheduling.scheduler().global().runLater(
                            Ticks.of(time), () -> executeDownloadCommand(ph));
                    time += 30L;
                }
            }
            if (reload) {
                FrameworkScheduling.scheduler().global().runLater(
                        Ticks.of(time + 30L), PlayerJoinListener::executeReloadCommand);
            }
        } catch (LinkageError error) {
            Bukkit.getLogger().warning("PlaceholderAPI became unavailable while handling player join: " + error.getClass().getSimpleName());
        }
    }

    private static final class PlaceholderApiBridge {
        private static boolean isRegistered(String placeholder) {
            return me.clip.placeholderapi.PlaceholderAPI.isRegistered(placeholder);
        }
    }
}

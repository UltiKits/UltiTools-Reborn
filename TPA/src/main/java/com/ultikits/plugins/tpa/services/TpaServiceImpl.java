package com.ultikits.plugins.tpa.services;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.plugins.tpa.tasks.Timer;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TPA 功能服务
 * @author qianmo
 * @version 1.0.0
 * @see com.ultikits.plugins.tpa.services.TpaService
 */
public class TpaServiceImpl implements TpaService{

    // TP传送请求
    private static final BiMap<Player, Player> TP_TEMP = HashBiMap.create();
    //TP_HERE传送请求
    private static final BiMap<Player, Player> TP_HERE_TEMP = HashBiMap.create();

    private static final TeleportService teleportService;

    static {
        Optional<TeleportService> service = PluginManager.getService(TeleportService.class);
        if (!service.isPresent()){
            throw new RuntimeException("Teleport Service Not Fount!");
        }
        teleportService = service.get();
    }

    @Override
    public String getName() {
        return "tpa";
    }

    @Override
    public String getAuthor() {
        return "qianmo";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public boolean requestTpa(Player requestPlayer, Player requestTarget, Player teleportPlayer) throws UnsupportedOperationException {
        if (!requestPlayer.isOnline() || !requestTarget.isOnline()) {
            return false;
        }
        if (requestPlayer.getUniqueId() == requestTarget.getUniqueId()) {
            throw new UnsupportedOperationException();
        }
        if (teleportPlayer == requestPlayer) {
            TP_TEMP.put(requestPlayer, requestTarget);
        } else if (teleportPlayer == requestTarget) {
            TP_HERE_TEMP.put(requestPlayer, requestTarget);
        } else {
            return false;
        }
        new Timer(requestTarget, 20).runTaskTimerAsynchronously(UltiTools.getInstance(),0, 20L);
        return true;
    }

    @Override
    public void responseTpa(Player player, boolean response) throws UnsupportedOperationException {
        if (response) {
            acceptTpa(player);
        } else {
            rejectTpa(player, false);
        }
    }

    @Override
    public void acceptTpa(Player player) throws UnsupportedOperationException {
        if (!player.isOnline()) {
            throw new UnsupportedOperationException();
        }
        if (!TP_TEMP.containsValue(player) && !TP_HERE_TEMP.containsValue(player)) {
            throw new UnsupportedOperationException();
        }
        if (TP_TEMP.inverse().get(player).isOnline()) {
            teleportService.teleport(TP_TEMP.inverse().get(player), player.getLocation());
            TP_TEMP.remove(TP_TEMP.inverse().get(player));
        } else if (TP_HERE_TEMP.inverse().get(player).isOnline()) {
            teleportService.teleport(player, TP_HERE_TEMP.inverse().get(player).getLocation());
            TP_HERE_TEMP.remove(TP_HERE_TEMP.inverse().get(player));
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void rejectTpa(Player player, boolean timeout) {
        TP_TEMP.remove(TP_TEMP.inverse().get(player));
        TP_HERE_TEMP.remove(TP_HERE_TEMP.inverse().get(player));
    }

    @Override
    public boolean isPlayerInTemp(Player player) {
        return TP_TEMP.containsKey(player) || TP_TEMP.containsValue(player) || TP_HERE_TEMP.containsKey(player) || TP_HERE_TEMP.containsValue(player);
    }

    @Override
    public void removePlayer(Player player) {
        TP_TEMP.remove(player);
        TP_TEMP.remove(TP_TEMP.inverse().get(player));
        TP_HERE_TEMP.remove(player);
        TP_HERE_TEMP.remove(TP_HERE_TEMP.inverse().get(player));
    }

    @Override
    public Player getOther(Player player) {
        if (TP_TEMP.containsKey(player)) {
            return TP_TEMP.get(player);
        }
        if (TP_TEMP.containsValue(player)) {
            return TP_TEMP.inverse().get(player);
        }
        if (TP_HERE_TEMP.containsKey(player)) {
            return TP_HERE_TEMP.get(player);
        }
        if (TP_HERE_TEMP.containsValue(player)) {
            return TP_HERE_TEMP.inverse().get(player);
        }
        return null;
    }

    @Override
    public boolean isPlayerIsRequested(Player player) {
        return TP_TEMP.containsValue(player) || TP_HERE_TEMP.containsValue(player);
    }

    @Override
    @Nullable
    public List<String> getTpTabList(@NotNull String[] strings) {
        List<String> tabCommands = new ArrayList<>();
        if (strings.length == 1) {
            tabCommands.add("accept");
            tabCommands.add("reject");
            for (Player player1 : Bukkit.getOnlinePlayers()) {
                tabCommands.add(player1.getName());
            }
            return tabCommands;
        }
        return null;
    }
}

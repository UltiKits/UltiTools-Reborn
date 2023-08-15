package com.ultikits.ultitools.services.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 传送服务实现类
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class InMemeryTeleportService implements TeleportService {
    private final static Map<UUID, Boolean> teleportingPlayers = new HashMap<>();
    private final static Map<UUID, String> locationMap = new HashMap<>();

    private final static Map<UUID, Location> inMemoryLocationRecord = new HashMap<>();

    static {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID playerUUID : teleportingPlayers.keySet()) {
                    if (!teleportingPlayers.get(playerUUID)) {
                        locationMap.put(playerUUID, null);
                        continue;
                    }
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player == null) {
                        continue;
                    }
                    Location location = player.getLocation();
                    String currentLocation = location.getX() + "" + location.getY() + "" + location.getZ();
                    if (locationMap.get(playerUUID) == null) {
                        locationMap.put(playerUUID, currentLocation);
                    } else {
                        String lastLocation = locationMap.get(playerUUID);
                        if (!currentLocation.equals(lastLocation)) {
                            InMemeryTeleportService.teleportingPlayers.put(playerUUID, false);
                        }
                    }
                }
            }
        }.runTaskTimerAsynchronously(UltiTools.getInstance(), 0, 10L);
    }

    @Override
    public void teleport(Player player, Location location) {
        inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
        player.teleport(location);
        player.playSound(player.getLocation(), UltiTools.getInstance().getVersionWrapper().getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
    }

    @Override
    public void delayTeleport(Player player, Location location, int delay) {
        teleportingPlayers.put(player.getUniqueId(), true);
        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load();
        }
        new BukkitRunnable() {
            float time = delay;

            @Override
            public void run() {
                if (!teleportingPlayers.get(player.getUniqueId())) {
                    player.sendTitle(ChatColor.RED + UltiTools.getInstance().i18n("传送失败！"), UltiTools.getInstance().i18n("请勿移动！"), 10, 50, 20);
                    this.cancel();
                    return;
                }
                if (time == 0) {
                    inMemoryLocationRecord.put(player.getUniqueId(), player.getLocation());
                    player.teleport(location);
                    player.playSound(player.getLocation(), UltiTools.getInstance().getVersionWrapper().getSound(Sounds.ENTITY_ENDERMAN_TELEPORT), 1, 0);
                    player.sendTitle(ChatColor.GREEN + UltiTools.getInstance().i18n("传送成功！"), "", 10, 50, 20);
                    teleportingPlayers.put(player.getUniqueId(), false);
                    this.cancel();
                    return;
                }
                if ((time / 0.5 % 2) == 0) {
                    player.sendTitle(ChatColor.GREEN + UltiTools.getInstance().i18n("传送中..."), String.format(UltiTools.getInstance().i18n("离传送还有%d秒"), (int) time), 10, 70, 20);
                }
                time -= 0.5;
            }
        }.runTaskTimer(UltiTools.getInstance(), 0, 10L);
    }

    @Override
    public Optional<Location> getLastTeleportLocation(UUID uuid) {
        return Optional.ofNullable(inMemoryLocationRecord.get(uuid));
    }

    @Override
    public String getName() {
        return "传送服务";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 1;
    }
}

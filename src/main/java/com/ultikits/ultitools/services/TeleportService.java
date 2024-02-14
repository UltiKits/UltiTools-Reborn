package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.BaseService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Teleport service.
 * <p>
 * 传送服务
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface TeleportService extends BaseService {
    /**
     * Teleport player instantly.
     * <p>
     * 立即传送玩家
     *
     * @param player   player <br> 玩家
     * @param location location <br> 地点
     */
    void teleport(Player player, Location location);

    /**
     * Delay teleport player.
     * <p>
     * 延迟传送玩家
     *
     * @param player   player <br> 玩家
     * @param location location <br> 地点
     * @param delay    delay <br> 延迟
     */
    void delayTeleport(Player player, Location location, int delay);

    /**
     * Get last teleport location of the player (before teleport).
     * <p>
     * 获取上次玩家使用此传送服务的地点（传送前）
     *
     * @param uuid player uuid <br> 玩家UUID
     * @return last teleport location <br> 上次传送地点
     */
    Optional<Location> getLastTeleportLocation(UUID uuid);
}

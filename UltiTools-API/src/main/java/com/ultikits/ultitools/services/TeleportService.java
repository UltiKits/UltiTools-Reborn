package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.BaseService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * 传送服务
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface TeleportService extends BaseService {
    /**
     * 立即传送玩家
     *
     * @param player   玩家
     * @param location 传送地点
     */
    void teleport(Player player, Location location);

    /**
     * 延迟传送玩家
     *
     * @param player   玩家
     * @param location 传送地点
     * @param delay    延迟秒数
     */
    void delayTeleport(Player player, Location location, int delay);

    /**
     * 获取上次玩家使用此传送服务的地点（传送前）
     *
     * @param uuid 玩家UUID
     * @return 地点
     */
    Optional<Location> getLastTeleportLocation(UUID uuid);
}

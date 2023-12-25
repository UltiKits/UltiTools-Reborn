package com.ultikits.plugins.home.services;

import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.ultitools.interfaces.BaseService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface HomeService extends BaseService {

    /**
     * 使用玩家UUID和家的名字获取到一个具体的家对象
     *
     * @param playerId 玩家的UUID
     * @param name     家的名字
     * @return 获取到的家对象
     */
    HomeEntity getHomeByName(UUID playerId, String name);

    /**
     * 使用玩家的UUID获取到玩家所有的家对象
     *
     * @param playerId 玩家的UUID
     * @return 玩家所有的家对象
     */
    List<HomeEntity> getHomeList(UUID playerId);

    List<String> getHomeNames(UUID playerId);

    /**
     * 使用玩家实体和家的名字创建一个家对象，家的名字不可重复
     *
     * @param player 玩家对象
     * @param name   家的名字
     * @return 返回是否创建成功
     */
    boolean createHome(Player player, String name);

    /**
     * 使用玩家的UUID和家的名字删除一个家
     *
     * @param playerId 玩家的UUID
     * @param name     家的名字
     */
    void deleteHome(UUID playerId, String name);

    void goHome(Player player, String name);
}

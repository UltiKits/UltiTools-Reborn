package com.ultikits.plugins.tpa.services;

import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * TPA 功能服务接口
 * @author qianmo
 * @version 1.0.0
 * @see com.ultikits.plugins.tpa.services.TpaServiceImpl
 */
public interface TpaService extends Registrable {

    /**
     * 发送传送请求
     * @implNote 要能够储存请求数据
     *
     * @apiNote 支持双向传送，需传入将被传送的玩家
     *
     * @param requestPlayer 请求传送的玩家
     * @param requestTarget 请求传送的目标玩家
     * @param teleportPlayer 被传送的玩家
     *
     * @return 发送结果： false 为未找到玩家，ture发送成功
     *
     * @throws UnsupportedOperationException 请求玩家与目标玩家为同一人时抛出异常
     */
    boolean requestTpa(Player requestPlayer, Player requestTarget, Player teleportPlayer) throws UnsupportedOperationException;


    /**
     * 回应传送请求
     * @implNote 未找到玩家要抛出异常，
     *
     * @apiNote 使用这个方法来回应传送请求
     *
     * @param player 回应请求的玩家
     * @param response 回应内容：true为同意，false为拒绝
     *
     * @throws UnsupportedOperationException 未找到玩家
     */
    void responseTpa(Player player, boolean response) throws UnsupportedOperationException;


    /**
     * 同意传送请求
     * @apiNote 同意
     *
     * @param player 同意请求的玩家
     */
    void acceptTpa(Player player);

    /**
     * 拒绝传送请求
     * @apiNote 拒绝
     *
     * @param player 拒绝请求的玩家
     * @param timeout 是否超时
     */
    void rejectTpa(Player player, boolean timeout);


    /**
     * @param player 查询玩家
     * @return 玩家是否在请求任务中
     */
    boolean isPlayerInTemp(Player player);


    /**
     * 将玩家从请求列队中移除
     * @param player 要移除的玩家
     */
    void removePlayer(Player player);


    /**
     * 通过给定的玩家来获取另一个玩家
     * @param player 已知的玩家
     * @return 另一个玩家，null为未找到玩家
     */
    Player getOther(Player player);


    /**
     * 判断玩家是否被请求
     * @param player 要判断的玩家
     * @return 是否被请求
     */
    boolean isPlayerIsRequested(Player player);


    /**
     * 获取命令补全
     * @param strings 命令参数
     * @return 补全列表
     */
    List<String> getTpTabList(@NotNull String[] strings);
}

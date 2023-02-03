package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public interface VersionWrapper {

    /**
     * 获取不同颜色的玻璃板
     *
     * @param plane 颜色
     * @return 玻璃板
     */
    ItemStack getColoredPlaneGlass(Colors plane);

    /**
     * 获取告示牌
     *
     * @return 告示牌
     */
    ItemStack getSign();

    /**
     * 获取末影之眼
     *
     * @return 末影之眼
     */
    ItemStack getEndEye();

    /**
     * 获取邮件材质
     *
     * @param isRead 是否已读
     * @return 邮件材质
     */
    ItemStack getEmailMaterial(boolean isRead);

    /**
     * 获取玩家头颅
     *
     * @param player 玩家
     * @return 玩家头颅
     */
    ItemStack getHead(OfflinePlayer player);

    /**
     * 获取玻璃块
     *
     * @return 玻璃块
     */
    ItemStack getGrassBlock();

    /**
     * 注册计分板对象
     *
     * @param scoreboard  计分板
     * @param name        名称
     * @param criteria    种类
     * @param displayName 名称
     * @return 计分板对象
     */
    Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName);

    /**
     * 获取声音
     *
     * @param sound 声音
     * @return 声音
     */
    Sound getSound(Sounds sound);

    /**
     * 获取床
     *
     * @param bedColor 颜色
     * @return 床
     */
    ItemStack getBed(Colors bedColor);

    /**
     * 获取物品耐久度
     *
     * @param itemStack 物品
     * @return 耐久度
     */
    int getItemDurability(ItemStack itemStack);

    /**
     * 获取玩家手中的物品
     *
     * @param player     玩家
     * @param isMainHand 主手还是副手
     * @return 物品
     */
    ItemStack getItemInHand(Player player, boolean isMainHand);

    /**
     * 给玩家发送Action Bar
     *
     * @param player  玩家
     * @param message 消息
     */
    void sendActionBar(Player player, String message);

    /**
     * 设置玩家头部显示
     *
     * @param player 玩家
     * @param header 标头
     * @param footer 标尾
     */
    void sendPlayerList(Player player, String header, String footer);

    /**
     * 获取方块面向
     *
     * @param placedBlock 方块
     * @return 方块面向
     */
    BlockFace getBlockFace(Block placedBlock);
}

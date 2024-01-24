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

/**
 * Version wrapper interface.
 * <p>
 * 版本包装器接口
 */
public interface VersionWrapper {

    /**
     * Get different colors of glass pane.
     * <p>
     * 获取不同颜色的玻璃板
     *
     * @param plane {@link Colors} color <br> 颜色
     * @return Glass pane <br> 玻璃板
     */
    ItemStack getColoredPlaneGlass(Colors plane);

    /**
     * Get sign.
     * <p>
     * 获取告示牌
     *
     * @return sign <br> 告示牌
     */
    ItemStack getSign();

    /**
     * Get end eye.
     * <p>
     * 获取末影之眼
     *
     * @return End eye <br> 末影之眼
     */
    ItemStack getEndEye();

    /**
     * Get email material.
     * <p>
     * 获取邮件材质
     *
     * @param isRead is read <br> 是否已读
     * @return material of the email <br> 邮件材质
     */
    ItemStack getEmailMaterial(boolean isRead);

    /**
     * Get player head
     * <br>
     * 获取玩家头颅
     *
     * @param player player <br> 玩家
     * @return player's head <br> 玩家头颅
     */
    ItemStack getHead(OfflinePlayer player);

    /**
     * Get glass block.
     * <br>
     * 获取玻璃块
     *
     * @return glass block <br> 玻璃块
     */
    ItemStack getGrassBlock();

    /**
     * Register scoreboard objective.
     * <br>
     * 注册计分板对象
     *
     * @param scoreboard  scoreboard <br> 计分板
     * @param name        name <br> 名称
     * @param criteria    criteria <br> 种类
     * @param displayName display name <br> 显示名称
     * @return 计分板对象
     */
    @Deprecated
    Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName);

    /**
     * Get sound.
     * <br>
     * 获取声音
     *
     * @param sound sound <br> 声音
     * @return Sound <br> 声音
     */
    Sound getSound(Sounds sound);

    /**
     * Get bed.
     * <br>
     * 获取床
     *
     * @param bedColor {@link Colors} bed color <br> 床颜色
     * @return Bed <br> 床
     */
    ItemStack getBed(Colors bedColor);

    /**
     * Get item durability.
     * <br>
     * 获取物品耐久度
     *
     * @param itemStack itemstack <br> 物品
     * @return durability <br> 耐久度
     */
    int getItemDurability(ItemStack itemStack);

    /**
     * Get item in player's hand.
     * <br>
     * 获取玩家手中的物品
     *
     * @param player     player <br> 玩家
     * @param isMainHand is main hand <br> 是否是主手
     * @return item in player's hand <br> 玩家手中的物品
     */
    ItemStack getItemInHand(Player player, boolean isMainHand);

    /**
     * Send action bar to player.
     * <br>
     * 给玩家发送Action Bar
     *
     * @param player  player <br> 玩家
     * @param message message <br> 消息
     */
    void sendActionBar(Player player, String message);

    /**
     * Send name tag to player.
     * <br>
     * 设置玩家头部显示
     *
     * @param player player <br> 玩家
     * @param header prefix <br> 前缀
     * @param footer suffix <br> 后缀
     */
    @Deprecated
    void sendPlayerList(Player player, String header, String footer);

    /**
     * Get block face.
     * <br>
     * 获取方块面向
     *
     * @param placedBlock placed block <br> 放置的方块
     * @return block face <br> 方块面向
     */
    BlockFace getBlockFace(Block placedBlock);
}

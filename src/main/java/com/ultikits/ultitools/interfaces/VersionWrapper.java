package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.utils.XVersionUtils;
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
 *
 * @deprecated Use {@link XVersionUtils} static methods instead.
 *             This interface is kept for backward compatibility only.
 *             Will be removed in a future version.
 *             <p>
 *             请改用 {@link XVersionUtils} 静态方法。
 *             此接口仅为向后兼容保留，将在未来版本中移除。
 * @removeIn 6.3.0
 */
@Deprecated(since = "6.2.0", forRemoval = true)
public interface VersionWrapper {

    /**
     * Get different colors of glass pane.
     * <p>
     * 获取不同颜色的玻璃板
     *
     * @param plane {@link Colors} color <br> 颜色
     * @return Glass pane <br> 玻璃板
     * @deprecated Use {@link XVersionUtils#getColoredPlaneGlass(Colors)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getColoredPlaneGlass(Colors plane) {
        return XVersionUtils.getColoredPlaneGlass(plane);
    }

    /**
     * Get sign.
     * <p>
     * 获取告示牌
     *
     * @return sign <br> 告示牌
     * @deprecated Use {@link XVersionUtils#getSign()} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getSign() {
        return XVersionUtils.getSign();
    }

    /**
     * Get end eye.
     * <p>
     * 获取末影之眼
     *
     * @return End eye <br> 末影之眼
     * @deprecated Use {@link XVersionUtils#getEndEye()} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getEndEye() {
        return XVersionUtils.getEndEye();
    }

    /**
     * Get email material.
     * <p>
     * 获取邮件材质
     *
     * @param isRead is read <br> 是否已读
     * @return material of the email <br> 邮件材质
     * @deprecated Use {@link XVersionUtils#getEmailMaterial(boolean)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getEmailMaterial(boolean isRead) {
        return XVersionUtils.getEmailMaterial(isRead);
    }

    /**
     * Get player head
     * <br>
     * 获取玩家头颅
     *
     * @param player player <br> 玩家
     * @return player's head <br> 玩家头颅
     * @deprecated Use {@link XVersionUtils#getHead(OfflinePlayer)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getHead(OfflinePlayer player) {
        return XVersionUtils.getHead(player);
    }

    /**
     * Get glass block.
     * <br>
     * 获取玻璃块
     *
     * @return glass block <br> 玻璃块
     * @deprecated Use {@link XVersionUtils#getGrassBlock()} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getGrassBlock() {
        return XVersionUtils.getGrassBlock();
    }

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
     * @deprecated Use {@link XVersionUtils#registerNewObjective(Scoreboard, String, String, String)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName) {
        return XVersionUtils.registerNewObjective(scoreboard, name, criteria, displayName);
    }

    /**
     * Get sound.
     * <br>
     * 获取声音
     *
     * @param sound sound <br> 声音
     * @return Sound <br> 声音
     * @deprecated Use {@link XVersionUtils#getSound(Sounds)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default Sound getSound(Sounds sound) {
        return XVersionUtils.getSound(sound);
    }

    /**
     * Get bed.
     * <br>
     * 获取床
     *
     * @param bedColor {@link Colors} bed color <br> 床颜色
     * @return Bed <br> 床
     * @deprecated Use {@link XVersionUtils#getBed(Colors)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getBed(Colors bedColor) {
        return XVersionUtils.getBed(bedColor);
    }

    /**
     * Get item durability.
     * <br>
     * 获取物品耐久度
     *
     * @param itemStack itemstack <br> 物品
     * @return durability <br> 耐久度
     * @deprecated Use {@link XVersionUtils#getItemDurability(ItemStack)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default int getItemDurability(ItemStack itemStack) {
        return XVersionUtils.getItemDurability(itemStack);
    }

    /**
     * Get item in player's hand.
     * <br>
     * 获取玩家手中的物品
     *
     * @param player     player <br> 玩家
     * @param isMainHand is main hand <br> 是否是主手
     * @return item in player's hand <br> 玩家手中的物品
     * @deprecated Use {@link XVersionUtils#getItemInHand(Player, boolean)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default ItemStack getItemInHand(Player player, boolean isMainHand) {
        return XVersionUtils.getItemInHand(player, isMainHand);
    }

    /**
     * Send action bar to player.
     * <br>
     * 给玩家发送Action Bar
     *
     * @param player  player <br> 玩家
     * @param message message <br> 消息
     * @deprecated Use {@link XVersionUtils#sendActionBar(Player, String)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default void sendActionBar(Player player, String message) {
        XVersionUtils.sendActionBar(player, message);
    }

    /**
     * Send name tag to player.
     * <br>
     * 设置玩家头部显示
     *
     * @param player player <br> 玩家
     * @param header prefix <br> 前缀
     * @param footer suffix <br> 后缀
     * @deprecated Use {@link XVersionUtils#sendPlayerList(Player, String, String)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default void sendPlayerList(Player player, String header, String footer) {
        XVersionUtils.sendPlayerList(player, header, footer);
    }

    /**
     * Get block face.
     * <br>
     * 获取方块面向
     *
     * @param placedBlock placed block <br> 放置的方块
     * @return block face <br> 方块面向
     * @deprecated Use {@link XVersionUtils#getBlockFace(Block)} instead.
     * @removeIn 6.3.0
     */
    @Deprecated(since = "6.2.0", forRemoval = true)
    default BlockFace getBlockFace(Block placedBlock) {
        return XVersionUtils.getBlockFace(placedBlock);
    }
}

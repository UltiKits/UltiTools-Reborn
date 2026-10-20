package com.ultikits.ultitools.utils;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Optional;

/**
 * Cross-version compatibility utility class using XSeries.
 * Replaces the old VersionWrapper dynamic loading mechanism.
 * Minimum supported version: 1.13+
 *
 * @author wisdomme
 * @since 6.2.0
 */
public final class XVersionUtils {

    private XVersionUtils() {
        // Utility class, no instantiation
    }

    /**
     * Get colored stained glass pane.
     *
     * @param color the color
     * @return colored glass pane ItemStack
     */
    public static ItemStack getColoredPlaneGlass(Colors color) {
        String materialName = color.name() + "_STAINED_GLASS_PANE";
        Optional<XMaterial> xMaterial = XMaterial.matchXMaterial(materialName);
        return xMaterial.map(XMaterial::parseItem).orElse(new ItemStack(Material.GLASS_PANE));
    }

    /**
     * Get colored wool.
     *
     * @param color the color
     * @return colored wool ItemStack
     */
    public static ItemStack getColoredWool(Colors color) {
        String materialName = color.name() + "_WOOL";
        Optional<XMaterial> xMaterial = XMaterial.matchXMaterial(materialName);
        return xMaterial.map(XMaterial::parseItem).orElse(new ItemStack(Material.WHITE_WOOL));
    }

    /**
     * Get oak sign.
     *
     * @return sign ItemStack
     */
    public static ItemStack getSign() {
        return XMaterial.OAK_SIGN.parseItem();
    }

    /**
     * Get ender eye.
     *
     * @return ender eye ItemStack
     */
    public static ItemStack getEndEye() {
        return XMaterial.ENDER_EYE.parseItem();
    }

    /**
     * Get email material (paper for unread, filled map for read).
     *
     * @param isRead whether the email is read
     * @return email material ItemStack
     */
    public static ItemStack getEmailMaterial(boolean isRead) {
        return isRead ? XMaterial.FILLED_MAP.parseItem() : XMaterial.PAPER.parseItem();
    }

    /**
     * Get player head.
     *
     * @param player the player
     * @return player head ItemStack
     */
    public static ItemStack getHead(OfflinePlayer player) {
        return player.isOp() ? XMaterial.DRAGON_HEAD.parseItem() : XMaterial.PLAYER_HEAD.parseItem();
    }

    /**
     * Get grass block.
     *
     * @return grass block ItemStack
     */
    public static ItemStack getGrassBlock() {
        return XMaterial.GRASS_BLOCK.parseItem();
    }

    /**
     * Register a new scoreboard objective.
     *
     * @param scoreboard  the scoreboard
     * @param name        objective name
     * @param criteria    criteria string
     * @param displayName display name
     * @return the objective
     */
    public static Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName) {
        Objective objective = scoreboard.getObjective(name);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(name, Criteria.DUMMY, displayName);
        }
        return objective;
    }

    /**
     * Get sound using XSound for cross-version compatibility.
     *
     * @param sound the sound enum
     * @return the bukkit Sound, or null if not found
     */
    public static Sound getSound(Sounds sound) {
        Optional<XSound> xSound = XSound.matchXSound(sound.name());
        return xSound.map(XSound::parseSound).orElse(null);
    }

    /**
     * Get colored bed.
     *
     * @param bedColor the bed color
     * @return bed ItemStack
     */
    public static ItemStack getBed(Colors bedColor) {
        String materialName = bedColor.name() + "_BED";
        Optional<XMaterial> xMaterial = XMaterial.matchXMaterial(materialName);
        return xMaterial.map(XMaterial::parseItem).orElse(new ItemStack(Material.RED_BED));
    }

    /**
     * Get item durability.
     *
     * @param itemStack the item
     * @return remaining durability
     */
    public static int getItemDurability(ItemStack itemStack) {
        if (itemStack.getItemMeta() instanceof Damageable) {
            Damageable damageable = (Damageable) itemStack.getItemMeta();
            return itemStack.getType().getMaxDurability() - damageable.getDamage();
        }
        return 0;
    }

    /**
     * Get item in player's hand.
     *
     * @param player     the player
     * @param isMainHand whether to get main hand item
     * @return item in hand
     */
    public static ItemStack getItemInHand(Player player, boolean isMainHand) {
        return isMainHand ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
    }

    /**
     * Send action bar message to player.
     *
     * @param player  the player
     * @param message the message
     */
    public static void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    /**
     * Set player list header and footer.
     *
     * @param player the player
     * @param header header text
     * @param footer footer text
     */
    public static void sendPlayerList(Player player, String header, String footer) {
        player.setPlayerListHeaderFooter(header, footer);
    }

    /**
     * Get block face direction.
     *
     * @param placedBlock the placed block
     * @return the block face
     */
    public static BlockFace getBlockFace(Block placedBlock) {
        BlockData blockData = placedBlock.getBlockData();
        if (blockData instanceof Directional) {
            return ((Directional) blockData).getFacing();
        }
        return BlockFace.NORTH;
    }
}

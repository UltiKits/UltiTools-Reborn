package com.ultikits.v1_20_R3;

import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.minecraft.network.chat.IChatBaseComponent;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.PacketPlayOutPlayerListHeaderFooter;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.craftbukkit.v1_20_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Objects;

public class Wrapper1_20_R3 implements VersionWrapper {

    public ItemStack getColoredPlaneGlass(Colors plane) {
        switch (plane) {
            case RED:
                return new ItemStack(Material.RED_STAINED_GLASS_PANE);
            case BLUE:
                return new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
            case CYAN:
                return new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
            case GRAY:
                return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            case LIME:
                return new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            case PINK:
                return new ItemStack(Material.PINK_STAINED_GLASS_PANE);
            case BLACK:
                return new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            case BROWN:
                return new ItemStack(Material.BROWN_STAINED_GLASS_PANE);
            case GREEN:
                return new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            case WHITE:
                return new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
            case ORANGE:
                return new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
            case PURPLE:
                return new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
            case YELLOW:
                return new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
            case MAGENTA:
                return new ItemStack(Material.MAGENTA_STAINED_GLASS_PANE);
            case LIGHT_BLUE:
                return new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
            case LIGHT_GRAY:
                return new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            default:
                return null;
        }
    }

    public ItemStack getSign() {
        return new ItemStack(Material.OAK_SIGN, 1);
    }

    public ItemStack getEndEye() {
        return new ItemStack(Material.ENDER_EYE, 1);
    }

    public ItemStack getEmailMaterial(boolean isRead) {
        if (isRead) {
            return new ItemStack(Material.FILLED_MAP, 1);
        } else {
            return new ItemStack(Material.PAPER, 1);
        }
    }

    public ItemStack getHead(OfflinePlayer player) {
        if (player.isOp()) {
            return new ItemStack(Material.DRAGON_HEAD);
        } else {
            return new ItemStack(Material.PLAYER_HEAD);
        }
    }

    public ItemStack getGrassBlock() {
        return new ItemStack(Material.GRASS_BLOCK, 1);
    }

    public Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName) {
        Objective objective = scoreboard.getObjective(name);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(name, criteria, displayName);
        }
        return objective;
    }

    public Sound getSound(Sounds sound) {
        switch (sound) {
            case UI_TOAST_OUT:
                return Sound.UI_TOAST_OUT;
            case BLOCK_CHEST_OPEN:
                return Sound.BLOCK_CHEST_OPEN;
            case BLOCK_CHEST_LOCKED:
                return Sound.BLOCK_CHEST_LOCKED;
            case ITEM_BOOK_PAGE_TURN:
                return Sound.ITEM_BOOK_PAGE_TURN;
            case BLOCK_NOTE_BLOCK_HAT:
                return Sound.BLOCK_NOTE_BLOCK_HAT;
            case BLOCK_NOTE_BLOCK_BELL:
                return Sound.BLOCK_NOTE_BLOCK_BELL;
            case BLOCK_WET_GRASS_BREAK:
                return Sound.BLOCK_WET_GRASS_BREAK;
            case BLOCK_NOTE_BLOCK_CHIME:
                return Sound.BLOCK_NOTE_BLOCK_CHIME;
            case ENTITY_ENDERMAN_TELEPORT:
                return Sound.ENTITY_ENDERMAN_TELEPORT;
            case BLOCK_CHEST_CLOSE:
                return Sound.BLOCK_CHEST_CLOSE;
            default:
                return null;
        }
    }

    public ItemStack getBed(Colors bedColor) {
        switch (bedColor) {
            case RED:
                return new ItemStack(Material.RED_BED);
            case BLUE:
                return new ItemStack(Material.BLUE_BED);
            case CYAN:
                return new ItemStack(Material.CYAN_BED);
            case GRAY:
                return new ItemStack(Material.GRAY_BED);
            case LIME:
                return new ItemStack(Material.LIME_BED);
            case PINK:
                return new ItemStack(Material.PINK_BED);
            case BLACK:
                return new ItemStack(Material.BLACK_BED);
            case BROWN:
                return new ItemStack(Material.BROWN_BED);
            case GREEN:
                return new ItemStack(Material.GREEN_BED);
            case WHITE:
                return new ItemStack(Material.WHITE_BED);
            case ORANGE:
                return new ItemStack(Material.ORANGE_BED);
            case PURPLE:
                return new ItemStack(Material.PURPLE_BED);
            case YELLOW:
                return new ItemStack(Material.YELLOW_BED);
            case MAGENTA:
                return new ItemStack(Material.MAGENTA_BED);
            case LIGHT_BLUE:
                return new ItemStack(Material.LIGHT_BLUE_BED);
            case LIGHT_GRAY:
                return new ItemStack(Material.LIGHT_GRAY_BED);
            default:
                return null;
        }
    }

    public int getItemDurability(ItemStack itemStack) {
        return itemStack.getType().getMaxDurability() - ((Damageable) Objects.requireNonNull(itemStack.getItemMeta())).getDamage();
    }

    public ItemStack getItemInHand(Player player, boolean isMainHand) {
        if (isMainHand) {
            return player.getInventory().getItemInMainHand();
        } else {
            return player.getInventory().getItemInOffHand();
        }
    }

    public void sendActionBar(Player player, String message) {
        try {
            IChatBaseComponent iChatBaseComponent = IChatBaseComponent.ChatSerializer.a("{\"text\": \"" + message + "\"}");
            ClientboundSystemChatPacket packetPlayOutChat = new ClientboundSystemChatPacket(iChatBaseComponent, true);
            ((CraftPlayer) player).getHandle().c.a(packetPlayOutChat);
        } catch (Exception e) {
            //Spigot 下解决 No Such Method Error
            BaseComponent[] baseComponents = new ComponentBuilder(message).create();
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, baseComponents);
        }
    }

    public void sendPlayerList(Player player, String header, String footer) {
        IChatBaseComponent tabHeader = IChatBaseComponent.ChatSerializer.a("{\"text\": \"" + header + "\"}");
        IChatBaseComponent tabFooter = IChatBaseComponent.ChatSerializer.a("{\"text\": \"" + footer + "\"}");
        PacketPlayOutPlayerListHeaderFooter tabPacket = new PacketPlayOutPlayerListHeaderFooter(tabHeader, tabFooter);
        ((CraftPlayer) player).getHandle().c.a(tabPacket);
    }

    @Override
    public BlockFace getBlockFace(Block placedBlock) {
        BlockData blockData = placedBlock.getBlockData();
        return ((Directional) blockData).getFacing();
    }
}

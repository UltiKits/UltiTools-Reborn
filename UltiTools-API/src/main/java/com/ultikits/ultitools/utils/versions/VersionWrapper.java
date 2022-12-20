package com.ultikits.ultitools.utils.versions;

import io.netty.channel.Channel;
import org.bukkit.DyeColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public interface VersionWrapper {
    ItemStack getItemStack(String material);

    ItemStack getItemStack(String material, int quantity);

    ItemStack getItemStack(String material, int quantity, String color);

    ItemStack getItemStack(String material, int quantity, String color, short damage);

    ItemStack getColoredPlaneGlass(DyeColor dyeColor);

    ItemStack getEmailMaterial(boolean isRead);

    ItemStack getHead(OfflinePlayer player);

    Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName);

    Sound getSound(String sound);

    ItemStack getBed(DyeColor bedColor);

    int getItemDurability(ItemStack itemStack);

    ItemStack getItemInHand(Player player, boolean isMainHand);

    void sendActionBar(Player player, String message);

    void sendPlayerList(Player player, String header, String footer);

    Object getPlayerConnection(Player player);

    Object getNetworkManager(Player player);

    Channel getChannel(Player player);

    Object getHandle(Player player);

    BlockFace getBlockFace(Block placedBlock);
}

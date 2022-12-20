package com.ultikits.ultitools.utils.versions.impl;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.versions.VersionWrapper;
import io.netty.channel.Channel;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class VersionWrapperImpl implements VersionWrapper {

    @Override
    public ItemStack getItemStack(String material) {
        return getItemStack(material, 1);
    }

    @Override
    public ItemStack getItemStack(String material, int quantity) {
        return new ItemStack(Material.valueOf(material), quantity);
    }

    @Override
    public ItemStack getItemStack(String material, int quantity, String color) {
        return getItemStack(material, quantity, color, (short) 0);
    }

    @Override
    public ItemStack getItemStack(String material, int quantity, String color, short damage) {
        return new ItemStack(Material.valueOf(material), quantity);
    }

    @Override
    public ItemStack getColoredPlaneGlass(DyeColor dyeColor) {
        return null;
    }

    @Override
    public ItemStack getEmailMaterial(boolean isRead) {
        return null;
    }

    @Override
    public ItemStack getHead(OfflinePlayer player) {
        return null;
    }

    @Override
    public Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName) {
        return null;
    }

    @Override
    public Sound getSound(String sound) {
        switch (getVersion()){
            case "1.19.2":
                return Sound.valueOf(sound);
        }
        return null;
    }

    @Override
    public ItemStack getBed(DyeColor bedColor) {
        return null;
    }

    @Override
    public int getItemDurability(ItemStack itemStack) {
        return 0;
    }

    @Override
    public ItemStack getItemInHand(Player player, boolean isMainHand) {
        return null;
    }

    @Override
    public void sendActionBar(Player player, String message) {

    }

    @Override
    public void sendPlayerList(Player player, String header, String footer) {

    }

    @Override
    public Object getPlayerConnection(Player player) {
        return null;
    }

    @Override
    public Object getNetworkManager(Player player) {
        return null;
    }

    @Override
    public Channel getChannel(Player player) {
        return null;
    }

    @Override
    public Object getHandle(Player player) {
        return null;
    }

    @Override
    public BlockFace getBlockFace(Block placedBlock) {
        return null;
    }

    public String getVersion() {
        String version = Bukkit.getVersion();
        return version.substring(version.lastIndexOf(" ") + 1, version.indexOf(")"));
    }
}

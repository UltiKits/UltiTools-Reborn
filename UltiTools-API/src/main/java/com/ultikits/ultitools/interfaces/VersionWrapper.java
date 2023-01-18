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

    ItemStack getColoredPlaneGlass(Colors plane);

    ItemStack getSign();

    ItemStack getEndEye();

    ItemStack getEmailMaterial(boolean isRead);

    ItemStack getHead(OfflinePlayer player);

    ItemStack getGrassBlock();

    Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName);

    Sound getSound(Sounds sound);

    ItemStack getBed(Colors bedColor);

    int getItemDurability(ItemStack itemStack);

    ItemStack getItemInHand(Player player, boolean isMainHand);

    void sendActionBar(Player player, String message);

    void sendPlayerList(Player player, String header, String footer);

    BlockFace getBlockFace(Block placedBlock);
}

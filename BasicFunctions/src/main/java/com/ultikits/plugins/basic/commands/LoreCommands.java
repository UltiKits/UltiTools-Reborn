package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"lore"}, manualRegister = true, permission = "ultikits.tools.command.lore", description = "物品Lore编辑功能")
public class LoreCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "add <lore...>")
    public void addLore(@CmdSender Player player, @CmdParam(value = "lore...", suggest = "[内容]") String[] lore) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return;
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        List<String> loreList = itemMeta.getLore();
        if (loreList == null) {
            loreList = new ArrayList<>();
        }
        String loreToAdd = String.join(" ", lore);
        loreList.add(coloredMsg(loreToAdd));
        itemMeta.setLore(loreList);
        itemStack.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(itemStack);
        player.sendMessage(ChatColor.GREEN + BasicFunctions.getInstance().i18n("物品Lore已修改"));
    }

    @CmdMapping(format = "delete <position>")
    public void deleteLore(@CmdSender Player player, @CmdParam(value = "position", suggest = "positionSuggest()") int position) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return;
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        List<String> loreList = itemMeta.getLore();
        if (loreList == null || loreList.isEmpty()) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("物品Lore为空"));
            return;
        }
        if (position > loreList.size() || position < 1) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("Lore行数超出范围"));
            return;
        }
        loreList.remove(position - 1);
        itemMeta.setLore(loreList);
        itemStack.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(itemStack);
        player.sendMessage(ChatColor.GREEN + BasicFunctions.getInstance().i18n("物品Lore已修改"));
    }

    @CmdMapping(format = "edit name <name>")
    public void editName(@CmdSender Player player, @CmdParam(value = "name", suggest = "[名称]") String name) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return;
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        itemMeta.setDisplayName(coloredMsg(name));
        itemStack.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(itemStack);
        player.sendMessage(ChatColor.GREEN + BasicFunctions.getInstance().i18n("物品Lore已修改"));
    }

    @CmdMapping(format = "edit <position> <lore...>")
    public void editLore(@CmdSender Player player, @CmdParam(value = "position", suggest = "positionSuggest()") int position, @CmdParam(value = "lore...", suggest = "[内容]") String[] lore) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return;
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        List<String> loreList = itemMeta.getLore();
        if (loreList == null || loreList.isEmpty()) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("物品Lore为空"));
            return;
        }
        if (position > loreList.size() || position < 1) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("Lore行数超出范围"));
            return;
        }
        String loreToEdit = String.join(" ", lore);
        if (position == 0) {
            itemMeta.setDisplayName(coloredMsg(loreToEdit));
        } else {
            loreList.set(position - 1, coloredMsg(loreToEdit));
            itemMeta.setLore(loreList);
        }
        itemStack.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(itemStack);
        player.sendMessage(ChatColor.GREEN + BasicFunctions.getInstance().i18n("物品Lore已修改"));
    }

    public List<String> positionSuggest(Player player) {
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return Collections.emptyList();
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        List<String> lore = itemMeta.getLore();
        if (lore == null || lore.isEmpty()) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("物品Lore为空"));
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= lore.size(); i++) {
            list.add(i + ". " + lore.get(i - 1));
        }
        return list;
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "lore add <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("添加Lore"));
        sender.sendMessage(ChatColor.RED + "lore delete <行数>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("删除Lore"));
        sender.sendMessage(ChatColor.RED + "lore edit <行数> <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("编辑Lore"));
        sender.sendMessage(ChatColor.RED + "lore edit name <名称>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("编辑物品名称"));
    }
}

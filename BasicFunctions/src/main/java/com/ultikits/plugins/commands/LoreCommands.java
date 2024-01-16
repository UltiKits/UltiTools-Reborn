package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"lore"}, manualRegister = true, permission = "ultikits.tools.command.lore", description = "物品Lore编辑功能")
public class LoreCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "add <lore...>")
    public void addLore(@CmdSender Player player, @CmdParam("lore...") String[] lore) {
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
    public void deleteLore(@CmdSender Player player, @CmdParam("position") int position) {
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

    @CmdMapping(format = "edit <position> <lore...>")
    public void editLore(@CmdSender Player player, @CmdParam("position") int position, @CmdParam("lore...") String[] lore) {
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

    @Override
    protected List<String> suggest(Player player, String[] strings) {
        switch (strings.length) {
            case 1:
                return Arrays.asList(
                        "add",
                        "delete",
                        "edit"
                );
            case 2:
            case 3:
                if (strings[0].equals("add")) {
                    return Collections.singletonList(BasicFunctions.getInstance().i18n("<内容>"));
                }
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

                if ((strings[0].equals("edit") || strings[0].equals("delete")) && strings.length == 2) {
                    return list;
                } else if (strings[0].equals("edit")) {
                    int position;
                    try {
                        position = Integer.parseInt(strings[1]);
                    } catch (NumberFormatException e) {
                        return Collections.singletonList(BasicFunctions.getInstance().i18n("<- 请输入对应的lore行数"));
                    }
                    if (position - 1 < lore.size() && position - 1 >= 0) {
                        return Collections.singletonList(lore.get(position - 1));
                    }
                }
                break;
            default:
                return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "lore add <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("添加Lore"));
        sender.sendMessage(ChatColor.RED + "lore delete <行数>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("删除Lore"));
        sender.sendMessage(ChatColor.RED + "lore edit <行数> <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("编辑Lore"));
    }
}

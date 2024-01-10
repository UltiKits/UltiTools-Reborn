package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class LoreCommands extends AbstractTabExecutor {

    @Override
    protected boolean onPlayerCommand(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        if (strings.length < 2) return false;
        ItemStack itemStack = player.getInventory().getItemInMainHand();
        if (itemStack.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("请手持物品来修改Lore"));
            return true;
        }
        ItemMeta itemMeta = Objects.requireNonNull(itemStack.getItemMeta());
        List<String> lore = itemMeta.getLore();
        Map<Integer, String> loreMap = new HashMap<>();
        if (lore != null) {
            for (int i = 0; i < lore.size(); i++) {
                loreMap.put(i, lore.get(i));
            }
        }
        List<String> content = new ArrayList<>(Arrays.asList(strings));
        content.remove(0);
        int position;
        switch (strings[0]) {
            case "add":
                String loreToAdd = String.join(" ", content);
                loreToAdd = ChatColor.translateAlternateColorCodes('&', loreToAdd);
                loreMap.put(loreMap.size(), loreToAdd);
                break;
            case "delete":
                try {
                    position = Integer.parseInt(strings[1]);
                }catch (NumberFormatException e) {
                    return false;
                }
                if (strings.length != 2) return false;
                if (lore == null || lore.isEmpty()) {
                    player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("物品Lore为空"));
                    return true;
                }
                if (position > lore.size() || position < 1){
                    return false;
                }
                loreMap.remove(position - 1);
                break;
            case "edit":
                try {
                    position = Integer.parseInt(strings[1]);
                }catch (NumberFormatException e) {
                    return false;
                }
                content.remove(0);
                String loreToEdit = String.join(" ", content);
                loreToEdit = ChatColor.translateAlternateColorCodes('&', loreToEdit);
                if (Objects.equals(strings[1], "0")) {
                    itemMeta.setDisplayName(loreToEdit);
                } else {
                    if (lore == null || lore.isEmpty()) {
                        player.sendMessage(ChatColor.RED + BasicFunctions.getInstance().i18n("物品Lore为空"));
                        return true;
                    }
                    if (position > lore.size() || position < 1){
                        return false;
                    }
                    loreMap.put(position - 1, loreToEdit);
                }
                break;
        }
        itemMeta.setLore(new ArrayList<>(loreMap.values()));
        itemStack.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(itemStack);
        player.sendMessage(ChatColor.GREEN + BasicFunctions.getInstance().i18n("物品Lore已修改"));
        return true;
    }

    @Nullable
    @Override
    protected List<String> onPlayerTabComplete(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
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
                }else if (strings[0].equals("edit")) {
                    int position;
                    try {
                        position = Integer.parseInt(strings[1]);
                    }catch (NumberFormatException e) {
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
    protected void sendHelpMessage(CommandSender commandSender) {
        commandSender.sendMessage(ChatColor.RED + "lore add <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("添加Lore"));
        commandSender.sendMessage(ChatColor.RED + "lore delete <行数>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("删除Lore"));
        commandSender.sendMessage(ChatColor.RED + "lore edit <行数> <内容>" + ChatColor.GRAY + " - " + BasicFunctions.getInstance().i18n("编辑Lore"));
    }
}

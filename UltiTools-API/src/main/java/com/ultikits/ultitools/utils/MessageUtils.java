package com.ultikits.ultitools.utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class MessageUtils {

    public static String msg(ChatColor chatColor, String message){
        return chatColor + message;
    }

    public static String info(String message){
        return ChatColor.AQUA + message;
    }

    public static String warning(String message){
        return ChatColor.RED + message;
    }

    public static String error(String message){
        return ChatColor.DARK_RED + message;
    }

    public static void broadcast(String message){
        // TODO 加上permission
        Bukkit.broadcast(ChatColor.YELLOW + message, "sb");
    }
}

package com.ultikits.plugins.basic.listeners;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.config.ChatConfig;
import com.ultikits.ultitools.annotations.EventListener;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.permissions.Permission;

import java.util.Set;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

@EventListener(manualRegister = true)
public class ChatListener implements Listener {

    @EventHandler
    public void onPlayerChatColor(AsyncPlayerChatEvent event) {
        //不启用
        //如果事件已经被取消
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        String Message = event.getMessage();
        //玩家说的内容
        if (Message.contains("&")) {
            //使用了彩色字体
            if (player.hasPermission(new Permission("ultikits.tools.chatcolor"))) {
                //如果玩家有"ultikits.tools.chatcolor"权限
                event.setMessage(coloredMsg(event.getMessage()));
                //输出颜色变量字体
            } else {
                //提醒玩家没权限
                player.sendMessage(BasicFunctions.getInstance().i18n("您没有权限发送彩色字体"));
            }
        }//没有使用不操作
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        ChatConfig config = BasicFunctions.getInstance().getConfig(ChatConfig.class);
        Player player = event.getPlayer();
        String prefixes = PlaceholderAPI.setPlaceholders(player, coloredMsg(config.getChatPrefix()));
        String message = prefixes + ChatColor.WHITE + " %2$s";
        event.setFormat(message);
    }

    @EventHandler
    public void onPlayerChatReply(AsyncPlayerChatEvent event) {
//        if (!UltiTools.getInstance().getConfig().getBoolean("enable_pro")) return;
        ChatConfig config = BasicFunctions.getInstance().getConfig(ChatConfig.class);
        String message = event.getMessage().replace(" ", "_");
        Set<String> keys = config.getAutoReply().keySet();
        String bestMatch = null;
        for (String each : keys) {
            if (message.contains(each)) {
                if (bestMatch != null) {
                    if (bestMatch.length() < each.length()) {
                        bestMatch = each;
                    }
                } else {
                    bestMatch = each;
                }
            }
        }
        String reply = config.getAutoReply().get(bestMatch);
        if (reply != null) {
            Bukkit.broadcastMessage(coloredMsg(reply));
        }
    }
}

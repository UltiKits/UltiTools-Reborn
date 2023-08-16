package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.tasks.TpTimerTask;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import com.ultikits.ultitools.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;


public class TpaHereCommands extends AbstractTabExecutor {
    @Nullable
    public static List<String> getTpTabList(@NotNull String[] strings) {
        List<String> tabCommands = new ArrayList<>();
        if (strings.length == 1) {
            tabCommands.add("accept");
            tabCommands.add("reject");
            for (Player player1 : Bukkit.getOnlinePlayers()) {
                tabCommands.add(player1.getName());
            }
            return tabCommands;
        }
        return null;
    }

    @Override
    protected boolean onPlayerCommand(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        if (strings.length == 1) {
            switch (strings[0]) {
                case "accept":
                    Player teleporter = TpTimerTask.tphereTemp.get(player);
                    if (teleporter == null) {
                        player.sendMessage(warning(BasicFunctions.getInstance().i18n("并没有玩家给你发送传送请求！")));
                        return true;
                    }
                    TpTimerTask.tphereTemp.put(player, null);
                    TpTimerTask.tphereTimer.put(player, 0);
                    player.teleport(teleporter.getLocation());
                    teleporter.sendMessage(info(BasicFunctions.getInstance().i18n("传送成功！")));
                    return true;
                case "reject":
                    Player teleporter2 = TpTimerTask.tphereTemp.get(player);
                    if (teleporter2 == null) {
                        player.sendMessage(warning(BasicFunctions.getInstance().i18n("并没有玩家给你发送传送请求！")));
                        return true;
                    }
                    teleporter2.sendMessage(warning(BasicFunctions.getInstance().i18n("对方拒绝了你的传送请求 :(")));
                    player.sendMessage(info(BasicFunctions.getInstance().i18n("已拒绝！")));
                    TpTimerTask.tphereTemp.put(player, null);
                    TpTimerTask.tphereTimer.put(player, 0);
                    return true;
                default:
                    Player target = Bukkit.getPlayerExact(strings[0]);
                    if (target == null) {
                        player.sendMessage(warning(BasicFunctions.getInstance().i18n("未找到目标，无法请求传送！")));
                        return true;
                    }
                    TpTimerTask.tphereTemp.put(target, player);
                    TpTimerTask.tphereTimer.put(target, 20);
                    player.sendMessage(info(String.format(BasicFunctions.getInstance().i18n("你已向%s发送TP请求！"), target.getName())));
                    target.sendMessage(info(String.format(BasicFunctions.getInstance().i18n("%s请求您传送至他的位置"), player.getName())));
                    TextComponent ask = Component
                            .text(BasicFunctions.getInstance().i18n("[同意]"))
                            .color(TextColor.color(0x00FF00))
                            .hoverEvent(Component.text(BasicFunctions.getInstance().i18n("点击同意传送")))
                            .clickEvent(ClickEvent.runCommand("/tphere accept"))
                            .append(Component.text(" "))
                            .append(
                                    Component
                                            .text(BasicFunctions.getInstance().i18n("[拒绝]"))
                                            .color(TextColor.color(0xFF0000))
                                            .hoverEvent(Component.text(BasicFunctions.getInstance().i18n("点击拒绝传送")))
                                            .clickEvent(ClickEvent.runCommand("/tphere reject"))
                            );
                    MessageUtils.sendMessage(target, ask);
                    return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    protected List<String> onPlayerTabComplete(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        return getTpTabList(strings);
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("----传送至此请求帮助----")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere accept 接受传送请求")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere reject 拒绝传送请求")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere [玩家名] 向玩家发送传送至此请求")));
    }
}

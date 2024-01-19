package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.suggests.CommonSuggest;
import com.ultikits.plugins.basic.tasks.TpTimerTask;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;


@CmdSuggest({CommonSuggest.class})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"tphere"}, manualRegister = true, permission = "ultikits.tools.command.tphere", description = "请求传送到此功能")
public class TpaHereCommands extends AbstractCommendExecutor {

    @CmdMapping(format = "accept")
    public void acceptTpa(@CmdSender Player player) {
        Player teleporter = TpTimerTask.tphereTemp.get(player);
        if (teleporter == null) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("并没有玩家给你发送传送请求！")));
            return;
        }
        TpTimerTask.tphereTemp.put(player, null);
        TpTimerTask.tphereTimer.put(player, 0);
        player.teleport(teleporter.getLocation());
        teleporter.sendMessage(info(BasicFunctions.getInstance().i18n("传送成功！")));
    }

    @CmdMapping(format = "reject")
    public void rejectTpa(@CmdSender Player player) {
        Player teleporter = TpTimerTask.tphereTemp.get(player);
        if (teleporter == null) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("并没有玩家给你发送传送请求！")));
            return;
        }
        teleporter.sendMessage(warning(BasicFunctions.getInstance().i18n("对方拒绝了你的传送请求 :(")));
        player.sendMessage(info(BasicFunctions.getInstance().i18n("已拒绝！")));
        TpTimerTask.tphereTemp.put(player, null);
        TpTimerTask.tphereTimer.put(player, 0);
    }

    @CmdMapping(format = "<player>")
    public void tpa(@CmdSender Player player,
                    @CmdParam(value = "player", suggest = "suggestPlayer") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("未找到目标，无法请求传送！")));
            return;
        }
        if (TpTimerTask.tphereTemp.get(target) != null) {
            player.sendMessage(warning(BasicFunctions.getInstance().i18n("对方正在处理另一个传送请求！")));
            return;
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
    }

//    @Override
//    protected List<String> suggest(Player player, Command command, String[] strings) {
//        return getTpTabList(strings);
//    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("----传送至此请求帮助----")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere accept 接受传送请求")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere reject 拒绝传送请求")));
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/tphere [玩家名] 向玩家发送传送至此请求")));
    }
}

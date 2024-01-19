package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"gm"}, manualRegister = true, permission = "ultikits.tools.command.gm", description = "游戏模式切换功能")
public class GMChangeCommand extends AbstractCommendExecutor {

    @CmdMapping(format = "0", permission = "ultikits.tools.command.gm.0")
    public void changeGameMode0(@CmdSender Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("生存模式")));
    }

    @CmdMapping(format = "1", permission = "ultikits.tools.command.gm.1")
    public void changeGameMode1(@CmdSender Player player) {
        player.setGameMode(GameMode.CREATIVE);
        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("创造模式")));
    }

    @CmdMapping(format = "2", permission = "ultikits.tools.command.gm.2")
    public void changeGameMode2(@CmdSender Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("冒险模式")));
    }

    @CmdMapping(format = "3", permission = "ultikits.tools.command.gm.3")
    public void changeGameMode3(@CmdSender Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(ChatColor.YELLOW + String.format(BasicFunctions.getInstance().i18n("你的游戏模式已设置为%s"), BasicFunctions.getInstance().i18n("旁观模式")));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "/gm <0/1/2/3>");
    }
}

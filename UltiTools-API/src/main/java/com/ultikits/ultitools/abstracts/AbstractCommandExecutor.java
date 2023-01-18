package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;

import java.util.Objects;

public abstract class AbstractCommandExecutor extends BukkitCommand {

    protected AbstractCommandExecutor(String name) {
        super(name);
    }

    protected abstract boolean playerExecute(Player player, String commandLabel, String[] args);

    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"));
            return true;
        }
        if (!sender.hasPermission(Objects.requireNonNull(this.getPermission()))) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("你没有权限！"));
            return true;
        }
        Player player = (Player) sender;
        return this.playerExecute(player, commandLabel, args);
    }
}

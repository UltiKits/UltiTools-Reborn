package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ultikits.ultitools.utils.MessageUtils.info;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"hide"}, manualRegister = true, permission = "ultikits.tools.command.hide", description = "隐身功能")
public class HideCommands extends AbstractCommendExecutor {
    private static final List<UUID> hidePlayers = new ArrayList<>();

    public static void removeHidePlayer(UUID uuid) {
        hidePlayers.remove(uuid);
    }

    @CmdMapping(format = "")
    public void hide(@CmdSender Player player) {
        if (hidePlayers.contains(player.getUniqueId())) {
            hidePlayers.remove(player.getUniqueId());
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getUniqueId().equals(player.getUniqueId())) {
                            continue;
                        }
                        p.showPlayer(UltiTools.getInstance(), player);
                    }
                }
            }.runTaskAsynchronously(UltiTools.getInstance());
            player.sendMessage(info(BasicFunctions.getInstance().i18n("你已退出隐身")));
        } else {
            hidePlayers.add(player.getUniqueId());
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getUniqueId().equals(player.getUniqueId())) {
                            continue;
                        }
                        p.hidePlayer(UltiTools.getInstance(), player);
                    }
                }
            }.runTaskAsynchronously(UltiTools.getInstance());
            player.sendMessage(info(BasicFunctions.getInstance().i18n("你已进入隐身")));
        }

    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(info(BasicFunctions.getInstance().i18n("/hide - 开启/关闭隐身")));
    }
}


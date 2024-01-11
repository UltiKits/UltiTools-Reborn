package com.ultikits.plugins.commands;

import com.nametagedit.plugin.NametagEdit;
import com.ultikits.plugins.BasicFunctions;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractPlayerCommandExecutor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ultikits.ultitools.utils.MessageUtils.info;

public class HideCommands extends AbstractPlayerCommandExecutor {
    private static final List<UUID> hidePlayers = new ArrayList<>();

    @Override
    protected boolean onPlayerCommand(@NotNull Command command, @NotNull String[] strings, @NotNull Player player) {
        if (strings.length != 0) return false;
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
        }
        player.sendMessage(info(BasicFunctions.getInstance().i18n("你已进入隐身")));
        return true;
    }

    @Override
    protected void sendHelpMessage(CommandSender commandSender) {
        commandSender.sendMessage(info(BasicFunctions.getInstance().i18n("/hide - 开启/关闭隐身")));
    }

    public static void removeHidePlayer(UUID uuid) {
        hidePlayers.remove(uuid);
    }
}


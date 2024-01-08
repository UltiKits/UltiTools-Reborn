package com.ultikits.plugins.commands;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.data.WarpData;
import com.ultikits.plugins.guis.WarpGui;
import com.ultikits.plugins.services.WarpService;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class WarpCommands extends AbstractTabExecutor {
    private final WarpService warpService = new WarpService();

    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        return executeWarpCommand(strings, player);
    }

    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        ArrayList<String> list = new ArrayList<>();
        switch (strings.length) {
            case 1:
                list.add("list");
                list.add("tp");
                if (player.isOp()) {
                    list.add("add");
                    list.add("remove");
                }
                return list;
            case 2:
                switch (strings[0]) {
                    case "remove":
                        if (player.isOp()) {
                            List<WarpData> allWarps = warpService.getAllWarps();
                            for (WarpData warpData : allWarps) {
                                list.add(warpData.getName());
                            }
                            return list;
                        }
                        return list;
                    case "tp":
                        List<WarpData> allWarps = warpService.getAllWarps();
                        for (WarpData warpData : allWarps) {
                            list.add(warpData.getName());
                        }
                        return list;
                    default:
                        return list;
                }
            default:
                return list;
        }
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp list §7- §e查看所有传送点"));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp tp <name> §7- §e传送至传送点"));
        if (sender.isOp()) {
            sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp add <name> §7- §e添加传送点"));
            sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp remove <name> §7- §e删除传送点"));
        }
    }

    private boolean executeWarpCommand(String[] strings, Player player) {
        switch (strings.length) {
            case 1:
                if (strings[0].equals("list")) {
                    WarpGui warpGui = new WarpGui(player);
                    warpGui.open();
                }
                return true;
            case 2:
                switch (strings[0]) {
                    case "add":
                        if (!player.isOp()) {
                            return false;
                        }
                        warpService.addWarp(strings[1], player.getLocation());
                        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已添加传送点 %s"), strings[1]));
                        return true;
                    case "remove":
                        if (!player.isOp()) {
                            return false;
                        }
                        warpService.removeWarp(strings[1]);
                        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已删除传送点 %s"), strings[1]));
                        return true;
                    case "tp":
                        Location warpLocation = warpService.getWarpLocation(strings[1]);
                        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已传送至传送点 %s"), strings[1]));
                        TeleportService teleportService = BasicFunctions.getInstance().getContext().getBean(TeleportService.class);
                        teleportService.delayTeleport(player, warpLocation, 3);
                        return true;
                    default:
                        return false;
                }
            default:
                return false;
        }
    }
}

package com.ultikits.plugins.basic.commands;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.data.WarpData;
import com.ultikits.plugins.basic.guis.WarpGui;
import com.ultikits.plugins.basic.services.WarpService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.services.TeleportService;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"warp"}, manualRegister = true, permission = "ultikits.tools.command.warp", description = "传送点功能")
public class WarpCommands extends AbstractCommendExecutor {
    @Autowired
    private WarpService warpService;

    @CmdMapping(format = "list")
    public void listWarps(@CmdSender Player player) {
        WarpGui warpGui = new WarpGui(player);
        warpGui.open();
    }

    @CmdMapping(format = "tp <name>")
    public void tpWarp(@CmdSender Player player, @CmdParam(value = "name", suggest = "warpSuggest") String name) {
        Location warpLocation = warpService.getWarpLocation(name);
        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已传送至传送点 %s"), name));
        TeleportService teleportService = BasicFunctions.getInstance().getContext().getBean(TeleportService.class);
        teleportService.delayTeleport(player, warpLocation, 3);
    }

    @CmdMapping(format = "add <name>", requireOp = true)
    public void addWarp(@CmdSender Player player, @CmdParam(value = "name", suggest = "[名称]") String name) {
        warpService.addWarp(name, player.getLocation());
        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已添加传送点 %s"), name));
    }

    @CmdMapping(format = "remove <name>", requireOp = true)
    public void removeWarp(@CmdSender Player player, @CmdParam(value = "name", suggest = "warpSuggest") String name) {
        warpService.removeWarp(name);
        player.sendMessage(String.format(BasicFunctions.getInstance().i18n("§a已删除传送点 %s"), name));
    }

    public List<String> warpSuggest(Player player) {
        ArrayList<String> list = new ArrayList<>();
        List<WarpData> allWarps = warpService.getAllWarps();
        for (WarpData warpData : allWarps) {
            list.add(warpData.getName());
        }
        return list;
    }

//    @Override
//    protected List<String> suggest(Player player, Command command, String[] strings) {
//        ArrayList<String> list = new ArrayList<>();
//        switch (strings.length) {
//            case 1:
//                list.add("list");
//                list.add("tp");
//                if (player.isOp()) {
//                    list.add("add");
//                    list.add("remove");
//                }
//                return list;
//            case 2:
//                switch (strings[0]) {
//                    case "remove":
//                        if (player.isOp()) {
//                            List<WarpData> allWarps = warpService.getAllWarps();
//                            for (WarpData warpData : allWarps) {
//                                list.add(warpData.getName());
//                            }
//                            return list;
//                        }
//                        return list;
//                    case "tp":
//                        List<WarpData> allWarps = warpService.getAllWarps();
//                        for (WarpData warpData : allWarps) {
//                            list.add(warpData.getName());
//                        }
//                        return list;
//                    default:
//                        return list;
//                }
//            default:
//                return list;
//        }
//    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp list §7- §e查看所有传送点"));
        sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp tp <name> §7- §e传送至传送点"));
        if (sender.isOp()) {
            sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp add <name> §7- §e添加传送点"));
            sender.sendMessage(BasicFunctions.getInstance().i18n("§a/warp remove <name> §7- §e删除传送点"));
        }
    }
}

package com.ultikits.plugins.kits.commands;

import com.ultikits.plugins.kits.gui.KitBrowserGui;
import com.ultikits.plugins.kits.gui.KitEditorGui;
import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Kit command executor.
 * 礼包命令执行器。
 */
@CmdExecutor(
        permission = "ultikits.kits.use",
        description = "礼包管理命令",
        alias = {"kits", "kit"}
)
public class KitCommands extends AbstractCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final KitService kitService;

    public KitCommands(UltiToolsPlugin plugin, KitService kitService) {
        this.plugin = plugin;
        this.kitService = kitService;
    }

    /**
     * /kits - Open kit browser GUI.
     */
    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void onOpenGui(@CmdSender Player player) {
        new KitBrowserGui(player, plugin, kitService, 0).open();
    }

    /**
     * /kits claim <name> - Claim a kit.
     */
    @CmdMapping(format = "claim <name>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void onClaim(
            @CmdSender Player player,
            @CmdParam(value = "name", suggest = "suggestKitNames") String name) {
        KitService.ClaimResult result = kitService.claimKit(player, name);
        handleClaimResult(player, name, result);
    }

    /**
     * /kits list - List all kits.
     */
    @CmdMapping(format = "list")
    public void onList(@CmdSender CommandSender sender) {
        List<KitDefinition> allKits;

        if (sender instanceof Player) {
            allKits = kitService.getAvailableKits((Player) sender);
        } else {
            allKits = kitService.getKitNames().stream()
                    .map(kitService::getKit)
                    .collect(Collectors.toList());
        }

        if (allKits.isEmpty()) {
            sender.sendMessage(ChatColor.RED + plugin.i18n("没有可用的礼包"));
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== " + plugin.i18n("礼包列表") + " ===");
        for (KitDefinition kit : allKits) {
            String displayName = ChatColor.translateAlternateColorCodes('&', kit.getDisplayName());
            String info = ChatColor.YELLOW + kit.getName() + ChatColor.GRAY + " - " + displayName;
            if (!kit.isFree()) {
                info += ChatColor.GOLD + " ($" + kit.getPrice() + ")";
            }
            sender.sendMessage(info);
        }
    }

    /**
     * /kits edit <name> - Open kit editor GUI.
     */
    @CmdMapping(format = "edit <name>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void onEdit(
            @CmdSender Player player,
            @CmdParam(value = "name", suggest = "suggestKitNames") String name) {
        if (!player.hasPermission("ultikits.kits.admin")) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限执行此命令"));
            return;
        }

        KitDefinition kit = kitService.getKit(name);
        if (kit == null) {
            player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包 '%s' 不存在"), name));
            return;
        }

        new KitEditorGui(player, plugin, kitService, kit).open();
    }

    /**
     * /kits create <name> - Create kit from current inventory.
     */
    @CmdMapping(format = "create <name>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void onCreate(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultikits.kits.admin")) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限执行此命令"));
            return;
        }

        KitService.CreateResult result = kitService.createKit(player, name);
        switch (result) {
            case SUCCESS:
                player.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("已创建礼包: %s"), name));
                break;
            case ALREADY_EXISTS:
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包名已存在: %s"), name));
                break;
            case INVALID_NAME:
                player.sendMessage(ChatColor.RED + plugin.i18n("无效的礼包名"));
                break;
            case EMPTY_INVENTORY:
                player.sendMessage(ChatColor.RED + plugin.i18n("物品栏为空"));
                break;
            default:
                player.sendMessage(ChatColor.RED + plugin.i18n("领取礼包时发生错误"));
                break;
        }
    }

    /**
     * /kits delete <name> - Delete a kit.
     */
    @CmdMapping(format = "delete <name>")
    public void onDelete(
            @CmdSender CommandSender sender,
            @CmdParam(value = "name", suggest = "suggestKitNames") String name) {
        if (!sender.hasPermission("ultikits.kits.admin")) {
            sender.sendMessage(ChatColor.RED + plugin.i18n("你没有权限执行此命令"));
            return;
        }

        if (kitService.deleteKit(name)) {
            sender.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("已删除礼包: %s"), name));
        } else {
            sender.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包 '%s' 不存在"), name));
        }
    }

    /**
     * /kits reload - Reload kit configurations.
     */
    @CmdMapping(format = "reload")
    public void onReload(@CmdSender CommandSender sender) {
        if (!sender.hasPermission("ultikits.kits.admin")) {
            sender.sendMessage(ChatColor.RED + plugin.i18n("你没有权限执行此命令"));
            return;
        }

        kitService.reload();
        int count = kitService.getAllKits().size();
        sender.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("已重新加载 %d 个礼包"), count));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiKits ===");
        sender.sendMessage(ChatColor.YELLOW + "/kits" + ChatColor.GRAY + " - " + plugin.i18n("礼包列表"));
        sender.sendMessage(ChatColor.YELLOW + "/kits claim <name>" + ChatColor.GRAY + " - " + plugin.i18n("可领取"));
        sender.sendMessage(ChatColor.YELLOW + "/kits list" + ChatColor.GRAY + " - " + plugin.i18n("礼包列表"));
        sender.sendMessage(ChatColor.YELLOW + "/kits edit <name>" + ChatColor.GRAY + " - Edit kit");
        sender.sendMessage(ChatColor.YELLOW + "/kits create <name>" + ChatColor.GRAY + " - Create kit");
        sender.sendMessage(ChatColor.YELLOW + "/kits delete <name>" + ChatColor.GRAY + " - Delete kit");
        sender.sendMessage(ChatColor.YELLOW + "/kits reload" + ChatColor.GRAY + " - Reload kits");
    }

    private void handleClaimResult(Player player, String kitName, KitService.ClaimResult result) {
        switch (result) {
            case SUCCESS:
                player.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("成功领取礼包: %s"), kitName));
                break;
            case NOT_FOUND:
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包 '%s' 不存在"), kitName));
                break;
            case NO_PERMISSION:
                player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限使用此礼包"));
                break;
            case INSUFFICIENT_LEVEL:
                KitDefinition kit = kitService.getKit(kitName);
                int level = kit != null ? kit.getLevelRequired() : 0;
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("等级不足，需要 %d 级"), level));
                break;
            case INSUFFICIENT_FUNDS:
                KitDefinition fundKit = kitService.getKit(kitName);
                String price = fundKit != null ? String.valueOf(fundKit.getPrice()) : "?";
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("余额不足，需要 %s"), price));
                break;
            case ALREADY_CLAIMED:
                player.sendMessage(ChatColor.RED + plugin.i18n("你已经领取过此礼包"));
                break;
            case ON_COOLDOWN:
                KitDefinition cdKit = kitService.getKit(kitName);
                long remaining = cdKit != null ? kitService.getRemainingCooldown(player, cdKit) : 0;
                String timeStr = kitService.formatCooldown(remaining);
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包冷却中，剩余: %s"), timeStr));
                break;
            case INVENTORY_FULL:
                player.sendMessage(ChatColor.RED + plugin.i18n("背包空间不足"));
                break;
            case EMPTY_KIT:
                player.sendMessage(ChatColor.RED + plugin.i18n("礼包内容为空"));
                break;
            default:
                player.sendMessage(ChatColor.RED + plugin.i18n("领取礼包时发生错误"));
                break;
        }
    }

    private List<String> suggestKitNames(Player player) {
        return kitService.getKitNames();
    }
}

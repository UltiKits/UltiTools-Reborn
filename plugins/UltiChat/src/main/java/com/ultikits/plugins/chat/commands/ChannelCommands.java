package com.ultikits.plugins.chat.commands;

import com.ultikits.plugins.chat.service.ChannelService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Channel switching commands for players.
 * 玩家频道切换命令。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(permission = "ultichat.channel", description = "Channel commands", alias = {"ch", "channel"})
@ConditionalOnConfig(value = "config/channels.yml", path = "channels.enabled")
public class ChannelCommands extends AbstractCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final ChannelService channelService;

    public ChannelCommands(UltiToolsPlugin plugin, ChannelService channelService) {
        this.plugin = plugin;
        this.channelService = channelService;
    }

    /**
     * List available channels: /ch list
     * 列出可用频道
     */
    @CmdMapping(format = "list")
    public void onList(@CmdSender CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return;
        }
        Player player = (Player) sender;

        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("channel_list_header")));

        List<String> channels = channelService.getAvailableChannels(player);
        if (channels.isEmpty()) {
            return;
        }

        String currentChannel = channelService.getPlayerChannel(player.getUniqueId());

        for (String channelName : channels) {
            String displayName = channelService.getChannelDisplayName(channelName);
            String line = plugin.i18n("channel_list_entry");
            line = line.replace("{0}", displayName);
            line = line.replace("{1}", channelName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }

        String currentMsg = plugin.i18n("channel_current")
                .replace("{0}", channelService.getChannelDisplayName(currentChannel));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', currentMsg));
    }

    /**
     * Switch to a channel: /ch <name>
     * 切换频道
     *
     * No @CmdTarget(PLAYER) here -- wildcard pattern would intercept "list"
     * before exact-match. Accept BOTH and check instanceof manually.
     */
    @CmdMapping(format = "<name>")
    public void onSwitch(
            @CmdSender CommandSender sender,
            @CmdParam(value = "name", suggest = "getName") String name
    ) {
        // Skip conflict with list subcommand
        if ("list".equalsIgnoreCase(name)) {
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return;
        }
        Player player = (Player) sender;

        // Check channel exists
        Map<String, Object> channelDef = channelService.getChannelDef(name);
        if (channelDef == null) {
            String msg = plugin.i18n("channel_not_found").replace("{0}", name);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }

        // Check permission
        if (!channelService.hasChannelPermission(player, name)) {
            String msg = plugin.i18n("channel_no_permission").replace("{0}", name);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return;
        }

        channelService.setPlayerChannel(player.getUniqueId(), name);
        String displayName = channelService.getChannelDisplayName(name);
        String msg = plugin.i18n("channel_switched").replace("{0}", displayName);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Channel Commands ===");
        sender.sendMessage(ChatColor.AQUA + "/ch list" + ChatColor.WHITE + " - List available channels");
        sender.sendMessage(ChatColor.AQUA + "/ch <name>" + ChatColor.WHITE + " - Switch to a channel");
    }

    @SuppressWarnings("unused")
    private List<String> getName(Player player) {
        return channelService.getAvailableChannels(player);
    }
}

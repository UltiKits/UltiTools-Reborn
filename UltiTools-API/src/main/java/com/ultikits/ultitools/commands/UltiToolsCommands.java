package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.List;

/**
 * 重载UltiTools-API的指令
 *
 * @author wisdomme, qianmo
 * @version 1.0.0
 */
@CmdExecutor(description = "UltiToolsCommands" ,alias = {"ul", "ultitools", "ulti"}, requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class UltiToolsCommands extends AbstractCommendExecutor {
    @CmdMapping(format = "reload")
    public void reloadPlugins() {
        try {
            UltiTools.getInstance().reloadPlugins();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @CmdMapping(format = "help")
    public void help(@CmdSender CommandSender sender) {
        handleHelp(sender);
    }

    @CmdMapping(format = "list")
    public void listPlugins(@CmdSender CommandSender sender) {
        List<UltiToolsPlugin> pluginList = UltiTools.getInstance().getPluginManager().getPluginList();
        for (UltiToolsPlugin plugin : pluginList) {
            sender.sendMessage(plugin.getPluginName() + " " + plugin.getVersion());
        }
    }

    /**
     * @param sender 命令发送者
     */
    @Override
    protected void handleHelp(CommandSender sender) {
        String help = "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul list 查看已加载的模块列表\n================";
        sender.sendMessage(UltiTools.getInstance().i18n(help));
    }
}

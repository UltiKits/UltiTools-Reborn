package com.ultikits.ultitools.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * The command that reloads UltiTools-API.
 *
 * @author wisdomme, qianmo
 * @version 1.0.0
 */
@CmdExecutor(description = "UltiToolsCommands", alias = {"ul", "ultitools", "ulti"}, requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class UltiToolsCommands extends BaseCommandExecutor {
    @CmdMapping(format = "reload")
    public void reloadPlugins() {
        try {
            UltiTools.getInstance().reloadPlugins();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @CmdMapping(format = "reload <name>")
    public void reloadPlugin(@CmdSender CommandSender sender,
                             @CmdParam(value = "name", suggest = "suggestModuleNames") String name) {
        List<UltiToolsPlugin> pluginList = UltiTools.getInstance().getPluginManager().getPluginList();
        for (UltiToolsPlugin plugin : pluginList) {
            if (plugin.getPluginName().equalsIgnoreCase(name)) {
                plugin.reloadSelf();
                sender.sendMessage(String.format(
                        UltiTools.getInstance().i18n("模块 %s 已重载"), name));
                return;
            }
        }
        sender.sendMessage(String.format(
                UltiTools.getInstance().i18n("模块 %s 不存在，请使用 /ul list 查看已加载的模块"), name));
    }

    public List<String> suggestModuleNames() {
        List<String> names = new ArrayList<>();
        for (UltiToolsPlugin plugin : UltiTools.getInstance().getPluginManager().getPluginList()) {
            names.add(plugin.getPluginName());
        }
        return names;
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
     * @param sender the command sender
     */
    @Override
    protected void handleHelp(CommandSender sender) {
        String help = "=== UltiTools 命令列表 ===\n/ul reload 重载插件模块\n/ul reload <模块名> 重载指定模块\n/ul list 查看已加载的模块列表\n================";
        sender.sendMessage(UltiTools.getInstance().i18n(help));
    }
}

package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.List;

@CmdExecutor(description = "UltiTools Plugin Management Commands", alias = "upm", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class PluginInstallCommands extends AbstractCommendExecutor {
    @CmdMapping(format = "list <page>")
    public void listPlugins(CommandSender sender, @CmdParam("page") String page) {
        int pageInt = 1;
        if (page != null && !page.isEmpty()) {
            try {
                pageInt = Integer.parseInt(page);
            } catch (NumberFormatException ignored) {
            }
        }
        List<PluginEntity> plugins = PluginInstallUtils.getPluginList(pageInt, 10);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(UltiTools.getInstance().i18n("========|可用插件列表|========\n"));
        int i = 1;
        for (PluginEntity plugin : plugins) {
            stringBuilder.append(i);
            stringBuilder.append(UltiTools.getInstance().i18n(".  名字："));
            stringBuilder.append(plugin.getName());
            stringBuilder.append("\n");
            stringBuilder.append(UltiTools.getInstance().i18n("   安装命令：/upm install "));
            stringBuilder.append(plugin.getIdentifyString());
            stringBuilder.append("\n");
            stringBuilder.append(UltiTools.getInstance().i18n("   简介："));
            stringBuilder.append(plugin.getShortDescription());
            stringBuilder.append("\n");
            if (i < plugins.size()) {
                stringBuilder.append("---------------------\n");
            }
            i++;
        }
        stringBuilder.append(String.format(UltiTools.getInstance().i18n("======== 第%d页 ========"), pageInt));
        sender.sendMessage(stringBuilder.toString());
    }

    @CmdMapping(format = "list")
    public void listPlugins(CommandSender sender) {
        listPlugins(sender, "1");
    }

    @CmdMapping(format = "install <plugin> <version>")
    public void installPlugin(CommandSender sender, @CmdParam("plugin") String plugin, @CmdParam("version") String version) {
        if (PluginInstallUtils.installPlugin(plugin, version)) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("安装成功！请重启服务器！"));
        } else {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("安装失败！"));
        }
    }

    @CmdMapping(format = "install <plugin>")
    public void installPlugin(CommandSender sender, @CmdParam("plugin") String plugin) {
        if (PluginInstallUtils.installLatestPlugin(plugin)) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("安装成功！请重启服务器！"));
        } else {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("安装失败！"));
        }
    }

    @CmdMapping(format = "versions <plugin>")
    public void listVersions(CommandSender sender, @CmdParam("plugin") String plugin) {
        List<String> pluginVersions = PluginInstallUtils.getPluginVersions(plugin);
        if (pluginVersions == null) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("获取版本列表失败！"));
            return;
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(UltiTools.getInstance().i18n("========|可用版本列表|========\n"));
        int i1 = 1;
        for (String version : pluginVersions) {
            stringBuilder.append(i1);
            stringBuilder.append(UltiTools.getInstance().i18n(". 版本："));
            stringBuilder.append(version);
            stringBuilder.append("\n");
            if (i1 == pluginVersions.size()) {
                stringBuilder.append(UltiTools.getInstance().i18n("   安装命令：/upm install "));
                stringBuilder.append(plugin);
                stringBuilder.append(" [版本]");
                stringBuilder.append("\n");
                stringBuilder.append("---------------------\n");
            }
            i1++;
        }
        sender.sendMessage(stringBuilder.toString());
    }

    @CmdMapping(format = "uninstall <plugin>")
    public void uninstallPlugin(CommandSender sender, @CmdParam("plugin") String plugin) {
        try {
            if (PluginInstallUtils.uninstallPlugin(plugin)) {
                sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("卸载成功！请手动删除本地文件，否则重启之后还会启用！"));
                sender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
            } else {
                sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("卸载失败！请检查是否拼写正确！"));
            }
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("删除失败！文件访问错误！请手动删除！"));
            sender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("========|插件安装帮助|========"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm list [页数] - 查看可用插件列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] - 安装最新插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] [版本] - 安装某版本插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm versions [插件] - 查看插件版本列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm uninstall [插件] - 删除插件"));
    }
}

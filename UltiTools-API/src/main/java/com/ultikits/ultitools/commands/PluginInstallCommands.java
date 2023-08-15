package com.ultikits.ultitools.commands;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.List;

public class PluginInstallCommands implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] strings) {
        if (!commandSender.isOp()) {
            return false;
        }
        if (strings.length > 0 && "help".equals(strings[0])) {
            sendHelpMessage(commandSender);
            return true;
        }
        if (strings.length == 0) {
            sendErrorMessage(commandSender, command);
            return false;
        }
        switch (strings[0]) {
            case "list":
                int page = 1;
                if (strings.length > 1) {
                    page = Integer.parseInt(strings[1]);
                }
                if (page < 1) {
                    page = 1;
                }
                List<PluginEntity> pluginList = PluginInstallUtils.getPluginList(page, 10);
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(UltiTools.getInstance().i18n("========|可用插件列表|========\n"));
                int i = 1;
                for (PluginEntity plugin : pluginList) {
                    stringBuilder.append(i);
                    stringBuilder.append(UltiTools.getInstance().i18n(". 名字："));
                    stringBuilder.append(plugin.getName());
                    stringBuilder.append("\n");
                    stringBuilder.append(UltiTools.getInstance().i18n("   安装命令：/upm install "));
                    stringBuilder.append(plugin.getIdentifyString());
                    stringBuilder.append("\n");
                    stringBuilder.append(UltiTools.getInstance().i18n("   简介："));
                    stringBuilder.append(plugin.getShortDescription());
                    stringBuilder.append("\n");
                    if (i < pluginList.size()) {
                        stringBuilder.append("---------------------\n");
                    }
                    i++;
                }
                stringBuilder.append(String.format(UltiTools.getInstance().i18n("======== 第%d页 ========"), page));
                commandSender.sendMessage(stringBuilder.toString());
                break;
            case "install":
                if (strings.length < 2) {
                    sendErrorMessage(commandSender, command);
                    return false;
                }
                boolean success;
                if (strings.length == 2) {
                    success = PluginInstallUtils.installLatestPlugin(strings[1]);
                } else {
                    success = PluginInstallUtils.installPlugin(strings[1], strings[2]);
                }
                if (!success) {
                    commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("安装失败！"));
                    return true;
                }
                commandSender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("安装成功！请重启服务器！"));
                break;
            case "versions":
                if (strings.length > 2) {
                    sendErrorMessage(commandSender, command);
                    return false;
                }
                List<String> pluginVersions = PluginInstallUtils.getPluginVersions(strings[1]);
                if (pluginVersions == null) {
                    commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("获取版本列表失败！"));
                    return true;
                }
                StringBuilder stringBuilder1 = new StringBuilder();
                stringBuilder1.append(UltiTools.getInstance().i18n("========|可用版本列表|========\n"));
                int i1 = 1;
                for (String version : pluginVersions) {
                    stringBuilder1.append(i1);
                    stringBuilder1.append(UltiTools.getInstance().i18n(". 版本："));
                    stringBuilder1.append(version);
                    stringBuilder1.append("\n");
                    if (i1 == pluginVersions.size()) {
                        stringBuilder1.append(UltiTools.getInstance().i18n("   安装命令：/upm install "));
                        stringBuilder1.append(strings[1]);
                        stringBuilder1.append(" [版本]");
                        stringBuilder1.append("\n");
                        stringBuilder1.append("---------------------\n");
                    }
                    i1++;
                }
                commandSender.sendMessage(stringBuilder1.toString());
                break;
            case "uninstall":
                if (strings.length > 2) {
                    sendErrorMessage(commandSender, command);
                    return false;
                }
                try {
                    if (PluginInstallUtils.uninstallPlugin(strings[1])) {
                        commandSender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("卸载成功！请手动删除本地文件，否则重启之后还会启用！"));
                        commandSender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
                    } else {
                        commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("卸载失败！请检查是否拼写正确！"));
                    }
                } catch (IOException e) {
                    commandSender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("删除失败！文件访问错误！请手动删除！"));
                    commandSender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
                }
                break;
        }
        return true;
    }

    protected void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("========|插件安装帮助|========"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm list [页数] - 查看可用插件列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] - 安装最新插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] [版本] - 安装某版本插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm versions [插件] - 查看插件版本列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm uninstall [插件] - 删除插件"));
    }

    private void sendErrorMessage(CommandSender sender, Command command) {
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("指令执行错误，请使用/%s %s获取帮助"), command.getName(), getHelpCommand()));
    }

    protected String getHelpCommand() {
        return "help";
    }
}

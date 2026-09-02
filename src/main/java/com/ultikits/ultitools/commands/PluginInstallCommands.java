package com.ultikits.ultitools.commands;

import java.io.IOException;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.utils.PluginInstallUtils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;

@CmdExecutor(description = "UltiTools Plugin Management Commands", alias = "upm", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class PluginInstallCommands extends BaseCommandExecutor {
    @CmdMapping(format = "list <page>")
    @RunAsync
    public void listPlugins(@CmdSender CommandSender sender, @CmdParam("page") String page) {
        int pageInt = 1;
        if (page != null && !page.isEmpty()) {
            try {
                pageInt = Integer.parseInt(page);
            } catch (NumberFormatException ignored) {
            }
        }
        List<UltiToolsPlugin> installedPlugins = UltiTools.getInstance().getPluginManager().getPluginList();
        List<PluginEntity> plugins = PluginInstallUtils.getPluginList(pageInt, 10);
        if (sender instanceof Player) {
            TextComponent text = Component.text(UltiTools.getInstance().i18n("========|可用插件列表|========\n"))
                    .color(TextColor.color(0x00ffff));
            int i = 1;
            for (PluginEntity plugin : plugins) {
                text = text.append(Component.text(i + UltiTools.getInstance().i18n(".  名字：") + plugin.getName() + "\n").color(TextColor.color(0x00ffff)));
                text = text.append(Component.text(UltiTools.getInstance().i18n("    安装状态：")).color(TextColor.color(127, 127, 127)));
                boolean installed = false;
                for (UltiToolsPlugin installedPlugin : installedPlugins) {
                    if (installedPlugin.getPluginName().equals(plugin.getName())) {
                        installed = true;
                        break;
                    }
                }
                if (installed) {
                    text = text.append(Component.text(UltiTools.getInstance().i18n(" 已安装") + "\n").color(TextColor.color(0x00ff00)));
                    text = text.append(
                            Component
                                    .text(UltiTools.getInstance().i18n("     | 卸载 |     ") + "\n")
                                    .color(TextColor.color(255, 0, 0))
                                    .hoverEvent(Component.text(UltiTools.getInstance().i18n("点击卸载模块")))
                                    .clickEvent(ClickEvent.runCommand("/upm uninstall " + plugin.getName()))
                    );
                } else {
                    text = text.append(Component.text(UltiTools.getInstance().i18n(" 未安装") + "\n").color(TextColor.color(0xff0000)));
                    text = text.append(
                            Component
                                    .text(UltiTools.getInstance().i18n("     | 安装 |     ") + "\n")
                                    .color(TextColor.color(0, 255, 0))
                                    .hoverEvent(Component.text(UltiTools.getInstance().i18n("点击安装模块")))
                                    .clickEvent(ClickEvent.runCommand("/upm install " + plugin.getIdentifyString()))
                    );
                }
                text = text.append(Component.text(UltiTools.getInstance().i18n("   简介：") + plugin.getShortDescription() + "\n").color(TextColor.color(127, 127, 127)));
                if (i < plugins.size()) {
                    text = text.append(Component.text("---------------------\n").color(TextColor.color(0x00ffff)));
                }
                i++;
            }
            text = text.append(Component.text(String.format(UltiTools.getInstance().i18n("======== 第%d页 ========"), pageInt))
                    .color(TextColor.color(0x00ffff)));
            TextComponent finalText = text;
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendComponentMessage((Player) sender, finalText);
                }
            }.runTask(UltiTools.getInstance());
        } else {
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
            String finalMessage = stringBuilder.toString();
            new BukkitRunnable() {
                @Override
                public void run() {
                    sendStringMessage(sender, finalMessage);
                }
            }.runTask(UltiTools.getInstance());
        }
    }

    /**
     * Send a TextComponent message to a player.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param player the player to send the message to
     * @param text   the text component to send
     */
    static void sendComponentMessage(Player player, TextComponent text) {
        MessageUtils.sendMessage(player, text);
    }

    /**
     * Send a string message to a command sender.
     * This method is extracted from BukkitRunnable for testability.
     *
     * @param sender  the sender to send the message to
     * @param message the message to send
     */
    static void sendStringMessage(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    @CmdMapping(format = "list")
    @RunAsync
    public void listPlugins(@CmdSender CommandSender sender) {
        listPlugins(sender, "1");
    }

    @CmdMapping(format = "install <plugin> <version>")
    public void installPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin, @CmdParam("version") String version) {
        if (PluginInstallUtils.installPlugin(plugin, version)) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("安装成功！请重启服务器！请务必删除旧版本模块！"));
        } else {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("安装失败！"));
        }
    }

    @CmdMapping(format = "install <plugin>")
    public void installPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
        if (PluginInstallUtils.installLatestPlugin(plugin)) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("安装成功！请重启服务器！请务必删除旧版本模块！"));
        } else {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("安装失败！"));
        }
    }

    @CmdMapping(format = "versions <plugin>")
    public void listVersions(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
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
                stringBuilder.append(" [version]");
                stringBuilder.append("\n");
                stringBuilder.append("---------------------\n");
            }
            i1++;
        }
        sender.sendMessage(stringBuilder.toString());
    }

    @CmdMapping(format = "uninstall <plugin>")
    public void uninstallPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
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

    @CmdMapping(format = "check")
    public void checkUpdates(@CmdSender CommandSender sender) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (updateManager == null || !updateManager.hasAnyUpdates()) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("没有可用的更新。"));
            return;
        }
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("可用更新列表："));
        UpdateInfo fwUpdate = updateManager.getFrameworkUpdate();
        if (fwUpdate != null) {
            sender.sendMessage(ChatColor.YELLOW + String.format("  UltiTools-API %s → %s",
                fwUpdate.getCurrentVersion(), fwUpdate.getLatestVersion()));
            sender.sendMessage(ChatColor.GRAY + "  " + String.format(
                UltiTools.getInstance().i18n("下载地址：%s"),
                "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
        }
        for (UpdateInfo info : updateManager.getModuleUpdates().values()) {
            sender.sendMessage(ChatColor.YELLOW + String.format("  %s %s → %s",
                info.getPluginName(), info.getCurrentVersion(), info.getLatestVersion()));
        }
    }

    @CmdMapping(format = "update <plugin>")
    @RunAsync
    public void updatePlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String pluginName) {
        if ("all".equalsIgnoreCase(pluginName)) {
            updateAllPlugins(sender);
            return;
        }
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (updateManager == null) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("更新失败！"));
            return;
        }
        UpdateInfo info = updateManager.getModuleUpdates().get(pluginName);
        if (info == null) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("没有可用的更新。"));
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + String.format(
            UltiTools.getInstance().i18n("正在更新 %s..."), pluginName));
        if (PluginInstallUtils.updatePlugin(info.getIdentifyString())) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("更新成功！请重启服务器以应用更新。"));
        } else {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("更新失败！"));
        }
    }

    private void updateAllPlugins(CommandSender sender) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (updateManager == null || updateManager.getModuleUpdates().isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("没有可用的更新。"));
            return;
        }
        int success = 0;
        int failed = 0;
        for (UpdateInfo info : updateManager.getModuleUpdates().values()) {
            sender.sendMessage(ChatColor.YELLOW + String.format(
                UltiTools.getInstance().i18n("正在更新 %s..."), info.getPluginName()));
            if (PluginInstallUtils.updatePlugin(info.getIdentifyString())) {
                success++;
            } else {
                failed++;
            }
        }
        sender.sendMessage(ChatColor.GREEN + String.format(
            UltiTools.getInstance().i18n("全部更新完成！%d个成功，%d个失败。请重启服务器。"),
            success, failed));
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("========|插件安装帮助|========"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm list [页数] - 查看可用插件列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] - 安装最新插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm install [插件] [版本] - 安装某版本插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm versions [插件] - 查看插件版本列表"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm uninstall [插件] - 删除插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm check - 查看可用更新"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm update [插件] - 更新插件"));
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("/upm update all - 更新所有插件"));
    }
}

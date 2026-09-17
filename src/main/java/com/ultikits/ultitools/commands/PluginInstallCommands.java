package com.ultikits.ultitools.commands;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
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
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
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

    /**
     * The literal Bukkit {@code name:} prefix {@link #isSameModule} strips before comparing a
     * loaded module's runtime name against the catalogue's display name (#439).
     */
    private static final String VENDOR_PREFIX = "UltiTools-";

    @CmdMapping(format = "list <page>")
    @RunAsync
    public void listPlugins(@CmdSender CommandSender sender, @CmdParam("page") String page) {
        int pageInt = 1;
        if (page != null && !page.isEmpty()) {
            try {
                pageInt = Integer.parseInt(page);
            } catch (NumberFormatException e) {
                // #380: this used to swallow the exception and serve page 1, so
                // `/upm list not-a-number` looked like it had worked. Report it in the same words
                // TypeParserRegistry uses for every other command's numeric argument, so the two
                // paths do not disagree about what a bad number looks like.
                sender.sendMessage(ChatColor.RED
                        + "Failed to parse '" + page + "' as Integer");
                return;
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
                String matchedRuntimeName = resolveInstalledRuntimeName(installedPlugins, plugin);
                boolean installed = matchedRuntimeName != null;
                if (installed) {
                    text = text.append(Component.text(UltiTools.getInstance().i18n(" 已安装") + "\n").color(TextColor.color(0x00ff00)));
                    text = text.append(
                            Component
                                    .text(UltiTools.getInstance().i18n("     | 卸载 |     ") + "\n")
                                    .color(TextColor.color(255, 0, 0))
                                    .hoverEvent(Component.text(UltiTools.getInstance().i18n("点击卸载模块")))
                                    // #439 gate-2 Codex round 1: the LOADED module's own runtime
                                    // name, not the catalogue's display name -- uninstallPlugin
                                    // matches only the former.
                                    .clickEvent(ClickEvent.runCommand("/upm uninstall " + matchedRuntimeName))
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
                // uninstallPlugin returns true only after every matching jar is deleted (#501), so
                // there is nothing left for the operator to remove by hand.
                sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("卸载成功！模块的 JAR 文件已全部删除。"));
            } else {
                sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("卸载失败！请检查是否拼写正确！"));
            }
        } catch (PluginModuleException e) {
            if (e.getErrorCode() != ErrorCode.PLUGIN_OPERATION_IN_PROGRESS) {
                throw e;
            }
            // An update or another uninstall of the same module is running (review r4 WR-03).
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("卸载失败！该模块正在进行更新或卸载，本次未做任何更改。"));
        } catch (IllegalStateException e) {
            // The module's own unload threw (review WR-01). It has still been removed from the
            // loaded modules and its jars still deleted where possible: report both, so the
            // operator knows whether it will come back on restart.
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("卸载出错！模块已从已加载列表移除，但其卸载过程抛出了异常，详见控制台。"));
            sendJarOutcomeAfterUnloadError(sender, e);
        } catch (NoSuchFileException e) {
            // The module was found by this exact name and unloaded, but no jar carries it (review
            // WR-03) -- a spelling hint would be false here.
            sender.sendMessage(ChatColor.YELLOW + String.format(UltiTools.getInstance().i18n("模块已卸载，但在 %s 中没有找到它的 JAR 文件。"), e.getFile()));
        } catch (FileSystemException e) {
            // Matching jars were found but not all deleted (#501): each loads the module again on
            // restart, so name every file the operator has to remove.
            sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("卸载失败！以下模块 JAR 文件无法删除，重启后模块会再次加载，请手动删除：%s"), namedFiles(e)));
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("删除失败！文件访问错误！请手动删除！"));
            sender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
        }
    }

    /**
     * Reports the jar outcome {@link PluginInstallUtils#uninstallPlugin} attached to its
     * unload-error exception: none attached means every matching jar was deleted.
     */
    private static void sendJarOutcomeAfterUnloadError(CommandSender sender, IllegalStateException unloadError) {
        for (Throwable jarFailure : unloadError.getSuppressed()) {
            if (jarFailure instanceof NoSuchFileException) {
                sender.sendMessage(ChatColor.YELLOW + String.format(UltiTools.getInstance().i18n("模块已卸载，但在 %s 中没有找到它的 JAR 文件。"), ((NoSuchFileException) jarFailure).getFile()));
                return;
            }
            if (jarFailure instanceof FileSystemException) {
                sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("卸载失败！以下模块 JAR 文件无法删除，重启后模块会再次加载，请手动删除：%s"), namedFiles((FileSystemException) jarFailure)));
                return;
            }
            if (jarFailure instanceof IOException) {
                sender.sendMessage(ChatColor.RED + UltiTools.getInstance().i18n("删除失败！文件访问错误！请手动删除！"));
                sender.sendMessage(ChatColor.GREEN + String.format(UltiTools.getInstance().i18n("文件位置：%s"), UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"));
                return;
            }
        }
        sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("模块的 JAR 文件已全部删除。"));
    }

    /**
     * Joins the file named by {@code failure} and by every {@code FileSystemException} suppressed
     * on it -- the shape {@link PluginInstallUtils} uses to report several files at once.
     */
    private static String namedFiles(FileSystemException failure) {
        List<String> files = new ArrayList<>();
        if (failure.getFile() != null) {
            files.add(failure.getFile());
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof FileSystemException && ((FileSystemException) suppressed).getFile() != null) {
                files.add(((FileSystemException) suppressed).getFile());
            }
        }
        return files.isEmpty()
                ? UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/plugins"
                : String.join(", ", files);
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
        sendUpdateOutcome(sender, PluginInstallUtils.updatePluginTransactionally(info.getIdentifyString()), false);
    }

    /**
     * Reports one staged update's outcome (#505, review r3). Every failure states whether the
     * modules folder was left unchanged; an old JAR that could not be moved back is named, because
     * the module would be missing after a restart until it is.
     */
    private void sendUpdateOutcome(CommandSender sender, PluginInstallUtils.UpdateOutcome outcome, boolean partOfUpdateAll) {
        UltiTools ultiTools = UltiTools.getInstance();
        switch (outcome.getStatus()) {
            case UPDATED:
                // Inside /upm update all the restart instruction waits for the summary: restarting on a
                // per-module line kills the loop inside a later module's update (review r4 WR-01).
                sender.sendMessage(ChatColor.GREEN + (partOfUpdateAll
                        ? ultiTools.i18n("更新成功。")
                        : ultiTools.i18n("更新成功！请重启服务器以应用更新。")));
                if (!outcome.getLeftoverFiles().isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + String.format(ultiTools.i18n("以下已移出模块目录的旧版本 JAR 未能删除，它们不会被加载，可手动删除：%s"),
                            String.join(", ", outcome.getLeftoverFiles())));
                }
                return;
            case ALREADY_IN_PROGRESS:
                sender.sendMessage(ChatColor.RED + ultiTools.i18n("更新失败！该模块正在进行另一项更新或卸载，本次未做任何更改。"));
                return;
            case INVALID_DOWNLOAD:
                sender.sendMessage(ChatColor.RED + ultiTools.i18n("更新失败！下载的文件不是该模块对应版本的有效 JAR，未做任何更改。"));
                return;
            case NEWER_VERSION_PRESENT:
                sender.sendMessage(ChatColor.RED + String.format(ultiTools.i18n("更新失败！模块目录中已有比最新目录版本 %s 更新的 JAR：%s（版本 %s），本次未做任何更改。"),
                        outcome.getExpectedVersion(), String.join(", ", outcome.getFiles()), outcome.getFoundVersion()));
                return;
            case STAGING_UNAVAILABLE:
                sender.sendMessage(ChatColor.RED + String.format(ultiTools.i18n("更新失败！无法准备暂存目录 %s，本次未做任何更改。"),
                        String.join(", ", outcome.getFiles())));
                sendFailureReason(sender, outcome);
                return;
            case FILE_SYSTEMS_DIFFER:
                sender.sendMessage(ChatColor.RED + String.format(ultiTools.i18n("更新失败！暂存目录 %s 与模块目录 %s 不在同一文件系统上，无法原子地替换 JAR，本次未做任何更改。"),
                        outcome.getFiles().isEmpty() ? "" : outcome.getFiles().get(0),
                        outcome.getFiles().size() < 2 ? "" : outcome.getFiles().get(1)));
                sendFailureReason(sender, outcome);
                sendUnrestored(sender, outcome);
                return;
            case OLD_JAR_NOT_MOVED:
                sender.sendMessage(ChatColor.RED + String.format(ultiTools.i18n("更新失败！无法将旧版本 JAR 文件移出模块目录：%s"),
                        String.join(", ", outcome.getFiles())));
                sendFailureReason(sender, outcome);
                sendRestoreResult(sender, outcome);
                return;
            case NEW_JAR_NOT_INSTALLED:
                sender.sendMessage(ChatColor.RED + String.format(ultiTools.i18n("更新失败！无法将新版本放入模块目录：%s"),
                        String.join(", ", outcome.getFiles())));
                sendFailureReason(sender, outcome);
                sendRestoreResult(sender, outcome);
                return;
            case DOWNLOAD_FAILED:
            default:
                sender.sendMessage(ChatColor.RED + ultiTools.i18n("更新失败！"));
        }
    }

    /** Names the failed file operation's cause, so "already exists" and "access denied" read differently (review r4 WR-04). */
    private static void sendFailureReason(CommandSender sender, PluginInstallUtils.UpdateOutcome outcome) {
        if (outcome.getFailureReason() != null) {
            sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("原因：%s"), outcome.getFailureReason()));
        }
    }

    private void sendRestoreResult(CommandSender sender, PluginInstallUtils.UpdateOutcome outcome) {
        if (outcome.getUnrestoredFiles().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + UltiTools.getInstance().i18n("旧版本已全部恢复，模块目录未做任何更改。"));
            return;
        }
        sendUnrestored(sender, outcome);
    }

    /**
     * Names each set-aside JAR that could not be moved back together with the exact original path,
     * file name included, to restore it to (review r4 WR-02): moved back under its staging name the
     * JAR is not loaded.
     */
    private static void sendUnrestored(CommandSender sender, PluginInstallUtils.UpdateOutcome outcome) {
        List<String> asides = outcome.getUnrestoredFiles();
        if (asides.isEmpty()) {
            return;
        }
        List<String> targets = outcome.getUnrestoredTargets();
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < asides.size(); i++) {
            pairs.add(i < targets.size() ? asides.get(i) + " -> " + targets.get(i) : asides.get(i));
        }
        sender.sendMessage(ChatColor.RED + String.format(UltiTools.getInstance().i18n("以下旧版本 JAR 未能移回模块目录。重启前请将每个文件移动到箭头后的路径，恢复其原文件名：%s"),
                String.join("; ", pairs)));
    }

    private void updateAllPlugins(CommandSender sender) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (updateManager == null || updateManager.getModuleUpdates().isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + UltiTools.getInstance().i18n("没有可用的更新。"));
            return;
        }
        int success = 0;
        int failed = 0;
        int skipped = 0;
        boolean unrestoredLeft = false;
        for (UpdateInfo info : updateManager.getModuleUpdates().values()) {
            sender.sendMessage(ChatColor.YELLOW + String.format(
                UltiTools.getInstance().i18n("正在更新 %s..."), info.getPluginName()));
            PluginInstallUtils.UpdateOutcome outcome =
                    PluginInstallUtils.updatePluginTransactionally(info.getIdentifyString());
            sendUpdateOutcome(sender, outcome, true);
            switch (outcome.getStatus()) {
                case UPDATED:
                    success++;
                    break;
                case ALREADY_IN_PROGRESS:
                    skipped++;
                    break;
                default:
                    failed++;
                    unrestoredLeft |= !outcome.getUnrestoredFiles().isEmpty();
            }
        }
        if (unrestoredLeft) {
            // Restarting before the old JARs named above are moved back loses those modules.
            sender.sendMessage(ChatColor.YELLOW + String.format(
                UltiTools.getInstance().i18n("全部更新完成！%d个成功，%d个失败，%d个跳过。请先按上面的提示将未能移回的旧版本 JAR 移回模块目录，再重启服务器。"),
                success, failed, skipped));
            return;
        }
        if (skipped > 0) {
            sender.sendMessage(ChatColor.GREEN + String.format(
                UltiTools.getInstance().i18n("全部更新完成！%d个成功，%d个失败，%d个因同一模块已有更新正在进行而跳过。请重启服务器。"),
                success, failed, skipped));
            return;
        }
        sender.sendMessage(ChatColor.GREEN + String.format(
            UltiTools.getInstance().i18n("全部更新完成！%d个成功，%d个失败。请重启服务器。"),
            success, failed));
    }

    /**
     * Decides whether {@code installedPlugin} (a currently loaded module) and {@code
     * catalogueEntry} (a directory entry from the UltiCloud catalogue) refer to the SAME module
     * (#439). The prior check compared {@code installedPlugin.getPluginName()} (the module's
     * Bukkit {@code plugin.yml} {@code name:}) against {@code catalogueEntry.getName()} (the
     * catalogue's own display name) with plain string equality -- correct only when the two
     * strings genuinely agree, which they do not for every module.
     * <p>
     * <b>Preferred key, but inert today: {@code identifyString}.</b> This is the field {@link
     * PluginInstallUtils} and {@link com.ultikits.ultitools.manager.UpdateManager} already treat
     * as this framework's stable module identity for install/update/version lookups, and the one
     * field {@link PluginEntity} and {@link UltiToolsPlugin} both carry under the same name.
     * Compared only when BOTH sides carry a non-blank value -- two identify-string-less entries
     * are never treated as a match on that basis alone, or every such module would appear
     * installed against every such catalogue entry, which is worse than the bug being fixed.
     * <b>Measured as of 6.3.0 (WR-01, `16-REVIEW-command.md`): zero of the seventeen module
     * directories under {@code Modules/} declare {@code identify-string:} in {@code plugin.yml}</b>
     * (verified: {@code grep -rl "identify-string:" Modules/*&#47;src/main/resources/plugin.yml}
     * returns nothing), so {@code installedPlugin.getIdentifyString()} is {@code null} for every
     * module that exists today, including both modules #439 names, and this branch does not fire
     * for any of them. It exists so a module that DOES declare one -- and any future migration
     * toward {@code identify-string:}-based identity -- is matched correctly without this method
     * needing to change again; today it is aspirational, not load-bearing.
     * <p>
     * <b>What actually fires today: exact name equality, plus one fixed, deterministic prefix
     * strip.</b> Measured (WR-01): only 4 of the 15 active modules declare a Bukkit {@code
     * name:} carrying the literal {@code "UltiTools-"} prefix -- {@code UltiTools-Chat}
     * ({@code Modules/UltiChat/src/main/resources/plugin.yml:1}), {@code UltiTools-Economy}
     * ({@code Modules/UltiEconomy/.../plugin.yml:1}), {@code UltiTools-Kits}
     * ({@code Modules/UltiKits/.../plugin.yml:1}), and {@code UltiTools-Menu}
     * ({@code Modules/UltiMenu/.../plugin.yml:1}) -- the last two ARE #439's own two named
     * modules. The other 11 modules declare their bare folder name with no prefix at all (e.g.
     * {@code UltiLogin}, {@code UltiWorlds}). This is NOT a declared or tool-enforced convention: {@code
     * Tooling/ultikits-cli/src/lib/templates.ts:152} templates {@code name:} directly from the
     * author-supplied module name with no prefix logic, and {@code
     * Tooling/UltiTools-Maven-Archetype} ships no {@code plugin.yml} template at all -- it is
     * simply each of these 4 authors' own historical choice. Stripping that one fixed prefix
     * before comparing is a single deterministic transform, not a similarity/fuzzy heuristic: it
     * accepts precisely the pairs that differ by nothing, or by exactly that one literal prefix,
     * and rejects every other pair -- including one that merely shares a prefix or substring (a
     * catalogue name that only resembles a loaded module's, such as one ending in {@code "Pro"},
     * is rejected, not matched).
     * <p>
     * <b>Two present identifiers are authoritative, even on a mismatch (gate-2 Codex round 1).</b>
     * When BOTH sides carry a non-blank {@code identifyString}, that comparison alone decides the
     * outcome -- a match returns {@code true} immediately, and a MISMATCH returns {@code false}
     * immediately, without ever falling through to the name/prefix heuristic below. Two present,
     * different stable identifiers prove the modules are different; the name heuristic must not
     * override that proof (a loaded {@code UltiTools-Foo} with id {@code author-a.foo} must not be
     * reported as the catalogue's unrelated {@code Foo} with id {@code author-b.foo}, even though
     * the name/prefix rule alone would match them). The comparison is normalised (trimmed,
     * lower-cased) via the same convention {@code PluginInstallUtils}'s own (private) {@code
     * normalizeIdentifyString} already applies before every install/update/lookup comparison
     * elsewhere in this package, so a module and a catalogue entry that agree except for case are
     * still recognised as the same.
     * <p>
     * <b>Residual collision risk (WR-01), disclosed rather than papered over:</b> the risk above
     * is closed whenever BOTH sides carry an identifier. It remains open only when at least one
     * side's {@code identifyString} is blank -- true for every module today (0/17) -- in which
     * case a THIRD-PARTY module that names itself {@code UltiTools-Foo} in its own {@code
     * plugin.yml} would still be reported installed against a different, unrelated author's
     * unaffiliated catalogue entry literally named {@code Foo}, since neither {@code
     * UltiToolsPlugin} nor {@code PluginEntity} exposes anything else (a developer/author ID) this
     * method could cross-check in that case. Still strictly better than the defect being fixed:
     * today, EVERY module whose name doesn't already match its catalogue display name
     * byte-for-byte is unconditionally reported not installed, a guaranteed false negative for
     * real, correctly-loaded modules. Closing the remaining gap needs a stable identifier
     * populated on both sides (#474), not a looser name heuristic.
     *
     * @param installedPlugin a currently loaded module
     * @param catalogueEntry  a directory entry from the catalogue
     * @return {@code true} iff the two are judged to refer to the same module
     */
    static boolean isSameModule(UltiToolsPlugin installedPlugin, PluginEntity catalogueEntry) {
        String moduleIdentify = normalizeIdentifyString(installedPlugin.getIdentifyString());
        String catalogueIdentify = normalizeIdentifyString(catalogueEntry.getIdentifyString());
        if (moduleIdentify != null && catalogueIdentify != null) {
            // Both sides carry a stable identifier -- AUTHORITATIVE. Never fall through to the
            // name/prefix heuristic below, even when the two identifiers disagree (gate-2 Codex
            // round 1): a present mismatch proves these are different modules.
            return moduleIdentify.equals(catalogueIdentify);
        }

        String runtimeName = installedPlugin.getPluginName();
        String catalogueName = catalogueEntry.getName();
        if (runtimeName == null || catalogueName == null) {
            return false;
        }
        if (runtimeName.equals(catalogueName)) {
            return true;
        }
        return stripVendorPrefix(runtimeName).equals(catalogueName);
    }

    /**
     * Resolves the runtime name to use for the {@code /upm uninstall} click command in {@link
     * #listPlugins(CommandSender, String)}'s player branch: the LOADED module's own {@code
     * getPluginName()}, not the catalogue entry's display name (gate-2 Codex round 1). {@link
     * PluginInstallUtils#uninstallPlugin} matches only against the runtime name -- for exactly
     * the display-name-mismatch case {@link #isSameModule} exists to recognise as installed,
     * using the catalogue's display name here would build an uninstall command that silently
     * fails (no unregister, no file delete, a generic failure message).
     *
     * @param installedPlugins every currently-loaded module
     * @param catalogueEntry   the catalogue entry being rendered
     * @return the matched module's own runtime name, or {@code null} if none matches
     */
    static String resolveInstalledRuntimeName(List<UltiToolsPlugin> installedPlugins, PluginEntity catalogueEntry) {
        for (UltiToolsPlugin installedPlugin : installedPlugins) {
            if (isSameModule(installedPlugin, catalogueEntry)) {
                return installedPlugin.getPluginName();
            }
        }
        return null;
    }

    /**
     * Trims and lower-cases an identify string, the same normalisation {@code
     * PluginInstallUtils}'s own (private) {@code normalizeIdentifyString} applies before every
     * install/update/lookup comparison elsewhere in this package (gate-2 Codex round 1) -- kept as
     * a small local copy rather than widening that method's visibility, since the transform
     * itself is a one-line, well-established convention, not shared mutable state.
     *
     * @param value the raw identify string, possibly {@code null} or blank
     * @return the normalised value, or {@code null} if the input was {@code null} or blank
     */
    private static String normalizeIdentifyString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String stripVendorPrefix(String runtimeName) {
        return runtimeName.startsWith(VENDOR_PREFIX)
                ? runtimeName.substring(VENDOR_PREFIX.length())
                : runtimeName;
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

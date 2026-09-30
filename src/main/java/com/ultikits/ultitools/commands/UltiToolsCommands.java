package com.ultikits.ultitools.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.exceptions.CommandException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * The command that reloads UltiTools-API.
 *
 * @author wisdomme, qianmo
 * @version 1.0.0
 */
@CmdExecutor(description = "UltiToolsCommands", alias = {"ul", "ultitools", "ulti"}, requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class UltiToolsCommands extends BaseCommandExecutor {
    /** Framework i18n key: the {@code /ul reload <name>} reply when the module's reload threw (#509). */
    static final String RELOAD_FAILED_REPLY_KEY = "Module %s failed to reload: %s. See the console for details.";

    /**
     * Framework i18n key: the {@code /ul reload <name>} reply when the module's reload hook reported
     * parts that did not reload (#529). The same text as the {@code /ul reload} summary line for
     * such a module, whose key {@code PluginManager} declares.
     */
    static final String RELOAD_PARTIAL_REPLY_KEY = "Module %s reloaded partially; not reloaded: %s";

    /**
     * Reloads the framework and every module. {@code /ul reload} is handled by {@link
     * #reloadAll(CommandSender)} since 6.3.0, which also replies the reload summary to the sender
     * (#509); this method is kept, unchanged, for any code that calls it directly.
     */
    public void reloadPlugins() {
        try {
            UltiTools.getInstance().reloadPlugins();
        } catch (IOException e) {
            // GATE-05 group two (08-21): routed to the typed command hierarchy -- this is a
            // core command's own execution failing.
            throw new CommandException(ErrorCode.COMMAND_EXECUTION_FAILED, "Failed to reload plugins", e);
        }
    }

    /**
     * {@code /ul reload}: reloads the framework and every module, each isolated from the others'
     * failures, and replies the summary to the sender -- which modules failed, if any (#509).
     *
     * @param sender the command sender
     */
    @CmdMapping(format = "reload")
    public void reloadAll(@CmdSender CommandSender sender) {
        List<String> summary;
        try {
            summary = UltiTools.getInstance().reloadPluginsAndReport();
        } catch (IOException e) {
            throw new CommandException(ErrorCode.COMMAND_EXECUTION_FAILED, "Failed to reload plugins", e);
        }
        for (String line : summary) {
            sender.sendMessage(line);
        }
    }

    @CmdMapping(format = "reload <name>")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // a module's reload failure becomes the reply -- #509
    public void reloadPlugin(@CmdSender CommandSender sender,
                             @CmdParam(value = "name", suggest = "suggestModuleNames") String name) {
        List<UltiToolsPlugin> pluginList = UltiTools.getInstance().getPluginManager().getPluginList();
        for (UltiToolsPlugin plugin : pluginList) {
            if (plugin.getPluginName().equalsIgnoreCase(name)) {
                // #509: a module whose reload threw has logged the failure with its stack trace;
                // the sender, who may have no console, is told it failed rather than getting no
                // reply at all. A VirtualMachineError is not the module's failure to report.
                ReloadReport report;
                try {
                    report = plugin.reloadWithReport();
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (RuntimeException | Error e) {
                    sender.sendMessage(String.format(UltiTools.getInstance().i18n(RELOAD_FAILED_REPLY_KEY),
                            plugin.getPluginName(), describeFailure(e)));
                    return;
                }
                // #529: a reload the module reported as partial is not answered with success.
                if (report.isPartial()) {
                    sender.sendMessage(String.format(UltiTools.getInstance().i18n(RELOAD_PARTIAL_REPLY_KEY),
                            plugin.getPluginName(), String.join("; ", report.getPartialReasons())));
                    return;
                }
                sender.sendMessage(String.format(
                        UltiTools.getInstance().i18n("模块 %s 已重载"), name));
                return;
            }
        }
        sender.sendMessage(String.format(
                UltiTools.getInstance().i18n("模块 %s 不存在，请使用 /ul list 查看已加载的模块"), name));
    }

    /**
     * A failure's message for a one-line reply, or its class name when it carries none.
     *
     * @param failure the failure to describe
     * @return a non-null description
     */
    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        return message != null ? message : failure.getClass().getName();
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

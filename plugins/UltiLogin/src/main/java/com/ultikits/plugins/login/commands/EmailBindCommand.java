package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.service.EmailVerificationService;
import com.ultikits.plugins.login.service.EmailVerificationService.BindResult;
import com.ultikits.plugins.login.service.EmailVerificationService.VerifyResult;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Email binding command executor.
 * /regs <email> - bind email
 * /regs <code> - verify email binding
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdExecutor(
    alias = {"regs"},
    permission = "ultilogin.email",
    description = "绑定邮箱"
)
public class EmailBindCommand extends BaseCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final EmailVerificationService emailVerificationService;
    private final LoginService loginService;

    public EmailBindCommand(UltiToolsPlugin plugin, EmailVerificationService emailVerificationService, LoginService loginService) {
        this.plugin = plugin;
        this.emailVerificationService = emailVerificationService;
        this.loginService = loginService;
    }

    @CmdMapping(format = "<arg>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void handleCommand(@CmdSender Player player, @CmdParam("arg") String arg) {
        // Must be logged in
        if (!loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("please_login_first")));
            return;
        }

        if (arg.contains("@")) {
            // Email binding: /regs user@example.com
            BindResult result = emailVerificationService.requestEmailBind(player, arg);
            String msg = plugin.i18n(result.getMessageKey());
            if (result.getReplacements() != null) {
                for (int i = 0; i < result.getReplacements().length - 1; i += 2) {
                    msg = msg.replace(result.getReplacements()[i], result.getReplacements()[i + 1]);
                }
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        } else {
            // Code verification: /regs 123456
            VerifyResult result = emailVerificationService.verifyEmailBind(player, arg);
            String msg = plugin.i18n(result.getMessageKey());
            if (result.getReplacements() != null) {
                for (int i = 0; i < result.getReplacements().length - 1; i += 2) {
                    msg = msg.replace(result.getReplacements()[i], result.getReplacements()[i + 1]);
                }
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("help_email_bind")));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("help_email_verify")));
    }
}

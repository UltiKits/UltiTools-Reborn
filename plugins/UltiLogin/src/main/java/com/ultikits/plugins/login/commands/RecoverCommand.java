package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.service.EmailVerificationService;
import com.ultikits.plugins.login.service.EmailVerificationService.RecoverResult;
import com.ultikits.plugins.login.service.EmailVerificationService.RecoverVerifyResult;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Password recovery command executor.
 * /recover - request recovery code via email
 * /recover <code> <password> <confirm> - reset password
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdExecutor(
    alias = {"recover"},
    permission = "ultilogin.recover",
    description = "找回密码"
)
public class RecoverCommand extends BaseCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final EmailVerificationService emailVerificationService;
    private final LoginService loginService;

    public RecoverCommand(UltiToolsPlugin plugin, EmailVerificationService emailVerificationService, LoginService loginService) {
        this.plugin = plugin;
        this.emailVerificationService = emailVerificationService;
        this.loginService = loginService;
    }

    /**
     * /recover - request recovery code
     */
    @CmdMapping(format = "")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void requestRecovery(@CmdSender Player player) {
        // Must NOT be logged in
        if (loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("recover_already_logged")));
            return;
        }

        RecoverResult result = emailVerificationService.requestPasswordRecovery(player);
        String msg = plugin.i18n(result.getMessageKey());
        if (result.getReplacements() != null) {
            for (int i = 0; i < result.getReplacements().length - 1; i += 2) {
                msg = msg.replace(result.getReplacements()[i], result.getReplacements()[i + 1]);
            }
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }

    /**
     * /recover <code> <password> <confirm> - reset password
     */
    @CmdMapping(format = "<code> <password> <confirm>")
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void resetPassword(
        @CmdSender Player player,
        @CmdParam("code") String code,
        @CmdParam("password") String newPassword,
        @CmdParam("confirm") String confirm
    ) {
        // Must NOT be logged in
        if (loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("recover_already_logged")));
            return;
        }

        // Check password match
        if (!newPassword.equals(confirm)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("password_mismatch")));
            return;
        }

        // Check password validity
        if (!loginService.isPasswordValid(newPassword)) {
            String error = loginService.getPasswordValidationError(newPassword);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', error));
            return;
        }

        // Verify code first
        RecoverVerifyResult verifyResult = emailVerificationService.verifyRecoveryCode(player, code);
        if (!verifyResult.isSuccess()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n(verifyResult.getMessageKey())));
            return;
        }

        // Reset password
        boolean success = emailVerificationService.resetPasswordAfterRecovery(player, newPassword);
        if (success) {
            loginService.completeLogin(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("recover_success")));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("recover_failed")));
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("help_recover")));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.i18n("help_recover_reset")));
    }
}

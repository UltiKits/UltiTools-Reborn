package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Change password command executor.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"changepassword", "changepw", "cpw"},
    permission = "ultilogin.changepassword",
    description = "修改密码"
)
public class ChangePasswordCommand extends BaseCommandExecutor {

    private final LoginService loginService;

    public ChangePasswordCommand(LoginService loginService) {
        this.loginService = loginService;
    }
    
    @CmdMapping(format = "<oldPassword> <newPassword> <confirm>")
    public void changePassword(
        @CmdSender Player player, 
        @CmdParam("oldPassword") String oldPassword,
        @CmdParam("newPassword") String newPassword,
        @CmdParam("confirm") String confirm
    ) {
        LoginConfig config = loginService.getConfig();
        
        // Check if logged in
        if (!loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "请先登录！");
            return;
        }
        
        // Validate new password based on mode
        if (!loginService.isPasswordValid(newPassword)) {
            String error = loginService.getPasswordValidationError(newPassword);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', error));
            return;
        }
        
        // Check confirm
        if (!newPassword.equals(confirm)) {
            player.sendMessage(ChatColor.RED + "两次输入的新密码不一致！");
            return;
        }
        
        // Change password
        if (loginService.changePassword(player.getUniqueId(), oldPassword, newPassword)) {
            player.sendMessage(ChatColor.GREEN + "密码修改成功！");
        } else {
            player.sendMessage(ChatColor.RED + "原密码错误！");
        }
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        LoginConfig config = loginService.getConfig();
        sender.sendMessage(ChatColor.YELLOW + "使用方法: /changepassword <旧密码> <新密码> <确认新密码>");
        if (config.isGuiModeEnabled()) {
            sender.sendMessage(ChatColor.GRAY + "密码必须是 " + config.getGuiPasswordLength() + " 位数字");
        } else {
            sender.sendMessage(ChatColor.GRAY + "密码长度: " + config.getMinPasswordLength() + "-" + config.getMaxPasswordLength() + " 字符");
        }
    }
}

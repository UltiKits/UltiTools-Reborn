package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Change password command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"changepassword", "changepw", "cpw"},
    permission = "ultilogin.changepassword",
    description = "修改密码"
)
public class ChangePasswordCommand extends AbstractCommendExecutor {
    
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
        // Check if logged in
        if (!loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "请先登录！");
            return;
        }
        
        // Validate new password
        if (newPassword.length() < loginService.getConfig().getMinPasswordLength()) {
            player.sendMessage(ChatColor.RED + "新密码太短！至少 " + loginService.getConfig().getMinPasswordLength() + " 个字符");
            return;
        }
        
        if (newPassword.length() > loginService.getConfig().getMaxPasswordLength()) {
            player.sendMessage(ChatColor.RED + "新密码太长！最多 " + loginService.getConfig().getMaxPasswordLength() + " 个字符");
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
        player.sendMessage(ChatColor.YELLOW + "使用方法: /changepassword <旧密码> <新密码> <确认新密码>");
    }
}

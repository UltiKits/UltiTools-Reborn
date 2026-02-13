package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.entity.AccountData;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Admin command executor for login management.
 * Commands:
 * - /logadmin reset <player> [password] - Reset player password
 * - /logadmin forcelogin <player> - Force login a player
 * - /logadmin unregister <player> - Delete player account
 * - /logadmin info <player> - Show player account info
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"logadmin", "loginadmin"},
    permission = "ultilogin.admin",
    description = "登录系统管理命令"
)
public class LoginAdminCommand extends BaseCommandExecutor {

    private final LoginService loginService;

    public LoginAdminCommand(LoginService loginService) {
        this.loginService = loginService;
    }
    
    /**
     * Reset player password with random password.
     */
    @CmdMapping(format = "reset <player>")
    public void resetPassword(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        LoginConfig config = loginService.getConfig();
        
        // Find account
        AccountData account = loginService.getAccountByName(playerName);
        if (account == null) {
            String message = config.getAdminAccountNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        // Reset password
        UUID uuid = UUID.fromString(account.getPlayerUuid());
        String newPassword = loginService.resetPassword(uuid);
        
        if (newPassword != null) {
            String message = config.getAdminPasswordReset()
                .replace("{PLAYER}", playerName)
                .replace("{PASSWORD}", newPassword);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            sender.sendMessage(ChatColor.RED + "密码重置失败！");
        }
    }
    
    /**
     * Reset player password with specific password.
     */
    @CmdMapping(format = "reset <player> <password>")
    public void resetPasswordWithValue(
        @CmdSender CommandSender sender, 
        @CmdParam("player") String playerName,
        @CmdParam("password") String password
    ) {
        LoginConfig config = loginService.getConfig();
        
        // Find account
        AccountData account = loginService.getAccountByName(playerName);
        if (account == null) {
            String message = config.getAdminAccountNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        // Validate password
        if (!loginService.isPasswordValid(password)) {
            String error = loginService.getPasswordValidationError(password);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', error));
            return;
        }
        
        // Reset password
        UUID uuid = UUID.fromString(account.getPlayerUuid());
        if (loginService.resetPassword(uuid, password)) {
            String message = config.getAdminPasswordReset()
                .replace("{PLAYER}", playerName)
                .replace("{PASSWORD}", password);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            sender.sendMessage(ChatColor.RED + "密码重置失败！");
        }
    }
    
    /**
     * Force login a player.
     */
    @CmdMapping(format = "forcelogin <player>")
    public void forceLogin(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        LoginConfig config = loginService.getConfig();
        
        Player player = Bukkit.getPlayer(playerName);
        if (player == null || !player.isOnline()) {
            String message = config.getAdminPlayerNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        if (!loginService.isRegistered(player.getUniqueId())) {
            String message = config.getAdminAccountNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        if (loginService.isLoggedIn(player.getUniqueId())) {
            sender.sendMessage(ChatColor.YELLOW + "玩家 " + playerName + " 已经登录！");
            return;
        }
        
        if (loginService.forceLogin(player)) {
            String message = config.getAdminForceLogin().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            player.sendMessage(ChatColor.GREEN + "管理员已帮助你登录！");
        } else {
            sender.sendMessage(ChatColor.RED + "强制登录失败！");
        }
    }
    
    /**
     * Unregister a player.
     */
    @CmdMapping(format = "unregister <player>")
    public void unregister(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        LoginConfig config = loginService.getConfig();
        
        // Find account
        AccountData account = loginService.getAccountByName(playerName);
        if (account == null) {
            String message = config.getAdminAccountNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        UUID uuid = UUID.fromString(account.getPlayerUuid());
        if (loginService.unregister(uuid)) {
            String message = config.getAdminUnregister().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            sender.sendMessage(ChatColor.RED + "删除账号失败！");
        }
    }
    
    /**
     * Show player account info.
     */
    @CmdMapping(format = "info <player>")
    public void showInfo(@CmdSender CommandSender sender, @CmdParam("player") String playerName) {
        LoginConfig config = loginService.getConfig();
        
        // Find account
        AccountData account = loginService.getAccountByName(playerName);
        if (account == null) {
            String message = config.getAdminAccountNotFound().replace("{PLAYER}", playerName);
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        sender.sendMessage(ChatColor.GOLD + "===== 玩家账号信息 =====");
        sender.sendMessage(ChatColor.YELLOW + "玩家名: " + ChatColor.WHITE + account.getPlayerName());
        sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + account.getPlayerUuid());
        sender.sendMessage(ChatColor.YELLOW + "注册IP: " + ChatColor.WHITE + account.getRegisterIp());
        sender.sendMessage(ChatColor.YELLOW + "最后IP: " + ChatColor.WHITE + account.getLastIp());
        sender.sendMessage(ChatColor.YELLOW + "登录次数: " + ChatColor.WHITE + account.getLoginCount());
        sender.sendMessage(ChatColor.YELLOW + "邮箱: " + ChatColor.WHITE + 
            (account.getEmail() != null ? account.getEmail() : "未绑定"));
        sender.sendMessage(ChatColor.YELLOW + "邮箱验证: " + ChatColor.WHITE + 
            (account.isEmailVerified() ? "已验证" : "未验证"));
        
        // Format timestamps
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sender.sendMessage(ChatColor.YELLOW + "注册时间: " + ChatColor.WHITE + 
            sdf.format(new java.util.Date(account.getRegisterTime())));
        sender.sendMessage(ChatColor.YELLOW + "最后登录: " + ChatColor.WHITE + 
            sdf.format(new java.util.Date(account.getLastLogin())));
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender CommandSender sender) {
        handleHelp(sender);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== UltiLogin 管理命令 =====");
        sender.sendMessage(ChatColor.YELLOW + "/logadmin reset <玩家> [密码]" + ChatColor.WHITE + " - 重置玩家密码");
        sender.sendMessage(ChatColor.YELLOW + "/logadmin forcelogin <玩家>" + ChatColor.WHITE + " - 强制登录玩家");
        sender.sendMessage(ChatColor.YELLOW + "/logadmin unregister <玩家>" + ChatColor.WHITE + " - 删除玩家账号");
        sender.sendMessage(ChatColor.YELLOW + "/logadmin info <玩家>" + ChatColor.WHITE + " - 查看玩家账号信息");
    }
}

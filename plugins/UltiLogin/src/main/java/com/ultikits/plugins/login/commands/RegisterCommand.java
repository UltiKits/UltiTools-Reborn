package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Register command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"register", "reg"},
    description = "注册账号"
)
public class RegisterCommand extends AbstractCommendExecutor {
    
    private final LoginService loginService;
    
    public RegisterCommand(LoginService loginService) {
        this.loginService = loginService;
    }
    
    @CmdMapping(format = "<password> <confirm>")
    public void register(@CmdSender Player player, @CmdParam("password") String password, @CmdParam("confirm") String confirm) {
        LoginConfig config = loginService.getConfig();
        
        // Check if already logged in
        if (loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getAlreadyLogged()));
            return;
        }
        
        // Check if already registered
        if (loginService.isRegistered(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getAlreadyRegistered()));
            return;
        }
        
        // Validate password length
        if (password.length() < config.getMinPasswordLength()) {
            String message = config.getPasswordTooShort().replace("{MIN}", String.valueOf(config.getMinPasswordLength()));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        if (password.length() > config.getMaxPasswordLength()) {
            String message = config.getPasswordTooLong().replace("{MAX}", String.valueOf(config.getMaxPasswordLength()));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        // Check password match
        if (!password.equals(confirm)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getPasswordMismatch()));
            return;
        }
        
        // Register
        if (loginService.register(player, password)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getRegisterSuccess()));
        }
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.YELLOW + "使用方法: /register <密码> <确认密码>");
    }
}

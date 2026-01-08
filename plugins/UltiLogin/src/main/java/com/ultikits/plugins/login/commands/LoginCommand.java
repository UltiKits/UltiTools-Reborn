package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Login command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"login", "l"},
    description = "登录账号"
)
public class LoginCommand extends AbstractCommendExecutor {
    
    private final LoginService loginService;
    
    public LoginCommand(LoginService loginService) {
        this.loginService = loginService;
    }
    
    @CmdMapping(format = "<password>")
    public void login(@CmdSender Player player, @CmdParam("password") String password) {
        LoginConfig config = loginService.getConfig();
        
        // Check if already logged in
        if (loginService.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getAlreadyLogged()));
            return;
        }
        
        // Check if registered
        if (!loginService.isRegistered(player.getUniqueId())) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getNotRegistered()));
            return;
        }
        
        // Login
        if (loginService.login(player, password)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getLoginSuccess()));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getWrongPassword()));
        }
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "使用方法: /login <密码>");
    }
}

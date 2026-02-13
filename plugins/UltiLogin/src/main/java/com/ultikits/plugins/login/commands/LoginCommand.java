package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Login command executor.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"login", "l"},
    description = "登录账号"
)
public class LoginCommand extends BaseCommandExecutor {

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
        
        // Check if locked
        if (loginService.isLocked(player)) {
            long remaining = loginService.getRemainingLockoutTime(player);
            String message = config.getAccountLocked().replace("{TIME}", String.valueOf(remaining));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        
        // Login with new result object
        LoginService.LoginResult result = loginService.login(player, password);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', result.getMessage()));
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

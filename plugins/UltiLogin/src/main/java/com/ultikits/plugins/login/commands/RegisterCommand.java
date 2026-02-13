package com.ultikits.plugins.login.commands;

import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Register command executor.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"register", "reg"},
    description = "注册账号"
)
public class RegisterCommand extends BaseCommandExecutor {

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
        
        // Validate password based on mode
        if (!loginService.isPasswordValid(password)) {
            String error = loginService.getPasswordValidationError(password);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', error));
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
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        LoginConfig config = loginService.getConfig();
        if (config.isGuiModeEnabled()) {
            sender.sendMessage(ChatColor.YELLOW + "使用方法: /register <密码> <确认密码>");
            sender.sendMessage(ChatColor.GRAY + "密码必须是 " + config.getGuiPasswordLength() + " 位数字");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "使用方法: /register <密码> <确认密码>");
            sender.sendMessage(ChatColor.GRAY + "密码长度: " + config.getMinPasswordLength() + "-" + config.getMaxPasswordLength() + " 字符");
        }
    }
}

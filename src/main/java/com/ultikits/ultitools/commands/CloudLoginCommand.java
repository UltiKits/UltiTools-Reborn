package com.ultikits.ultitools.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.utils.ApiRateLimiter;
import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * Commands for UltiCloud authentication via magic link.
 * <br>
 * 通过魔法链接进行UltiCloud身份验证的命令。
 *
 * Usage:
 *   /ulticloud login  — Generate a magic link to authenticate
 *   /ulticloud logout — Clear saved credentials
 *   /ulticloud status — Show current auth status
 */
@CmdExecutor(description = "UltiCloud Authentication Commands", alias = "ulticloud", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class CloudLoginCommand extends AbstractCommandExecutor {

    @CmdMapping(format = "login")
    @RunAsync
    public void login(@CmdSender CommandSender sender) {
        if (CloudAuthManager.hasValidToken()) {
            sender.sendMessage(ChatColor.YELLOW + "Already logged in to UltiCloud. Use /ulticloud logout first to re-login.");
            return;
        }

        if (!ApiRateLimiter.isLoginAllowed()) {
            long remaining = ApiRateLimiter.getRemainingCooldown("login", 60_000);
            sender.sendMessage(ChatColor.RED + "Please wait " + remaining + " seconds before trying again.");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "Requesting login link from UltiCloud...");

        String url = CloudAuthManager.requestMagicLink(error -> {
            sender.sendMessage(ChatColor.RED + "Failed to get login link: " + error);
        });

        if (url != null) {
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GREEN + " Open this URL in your browser to login:");
            sender.sendMessage(ChatColor.AQUA + " " + url);
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GRAY + "The link will expire in 5 minutes.");
            sender.sendMessage(ChatColor.GRAY + "Waiting for authentication...");
        }
    }

    @CmdMapping(format = "logout")
    public void logout(@CmdSender CommandSender sender) {
        if (!CloudAuthManager.hasValidToken()) {
            sender.sendMessage(ChatColor.YELLOW + "Not currently logged in to UltiCloud.");
            return;
        }

        try {
            CloudAuthManager.clearToken();
            sender.sendMessage(ChatColor.GREEN + "Successfully logged out of UltiCloud. Cloud features are now disabled.");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to re-authenticate.");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to logout: " + e.getMessage());
        }
    }

    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        if (CloudAuthManager.hasValidToken()) {
            TokenEntity token = CloudAuthManager.getCurrentToken();
            String username = token.getUser_name() != null ? token.getUser_name() : "Unknown";
            sender.sendMessage(ChatColor.GREEN + "UltiCloud: Connected as " + username);
            if (token.getExpirationDate() != null) {
                sender.sendMessage(ChatColor.GRAY + "Token expires: " + token.getExpirationDate().toString());
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "UltiCloud: Not connected");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to authenticate.");
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== UltiCloud Commands ===");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud login" + ChatColor.GRAY + " - Authenticate with UltiCloud");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud logout" + ChatColor.GRAY + " - Clear saved credentials");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY + " - Show connection status");
    }
}

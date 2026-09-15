package com.ultikits.ultitools.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * Commands for UltiCloud authentication via magic link.
 *
 * Usage:
 *   /ulticloud login  — Generate a magic link to authenticate
 *   /ulticloud logout — Clear saved credentials
 *   /ulticloud status — Show current auth status
 */
@CmdExecutor(description = "UltiCloud Authentication Commands", alias = "ulticloud", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class CloudLoginCommand extends BaseCommandExecutor {

    @CmdMapping(format = "login")
    @RunAsync
    public void login(@CmdSender CommandSender sender) {
        // Decision logic (already-logged-in / rate-limit / request / poll) lives behind
        // CloudAuthManager.login() as of plan 16-09 (D-17/D-18) -- this method now only supplies
        // the exact same message strings as callbacks, so /ulticloud login's console output is
        // byte-for-byte unchanged.
        CloudAuthManager.login(
            () -> sender.sendMessage(ChatColor.YELLOW
                + "Already logged in to UltiCloud. Use /ulticloud logout first to re-login."),
            remaining -> sender.sendMessage(ChatColor.RED
                + "Please wait " + remaining + " seconds before trying again."),
            () -> sender.sendMessage(ChatColor.AQUA + "Requesting login link from UltiCloud..."),
            url -> {
                sender.sendMessage(ChatColor.GREEN + "========================================");
                sender.sendMessage(ChatColor.GREEN + " Open this URL in your browser to login:");
                sender.sendMessage(ChatColor.AQUA + " " + url);
                sender.sendMessage(ChatColor.GREEN + "========================================");
                sender.sendMessage(ChatColor.GRAY + "The link will expire in 5 minutes.");
                sender.sendMessage(ChatColor.GRAY + "Waiting for authentication...");
            },
            error -> sender.sendMessage(ChatColor.RED + "Failed to get login link: " + error)
        );
    }

    @CmdMapping(format = "logout")
    public void logout(@CmdSender CommandSender sender) {
        // This deliberately does **not** use hasValidToken() as the gate.
        //
        // It requires the token to be unexpired, but "the access token has expired while
        // everything else is still running" is exactly the scenario that most needs logout to
        // take effect: after repeated refresh failures, the socket, the monitor task, the log
        // handler on the root logger, the player event listener and the refresh schedule can
        // all still be alive, and logout is the operator's only way to stop them. The previous
        // implementation replied "Not currently logged in" at this point and exited, leaving the
        // operator with no option but a restart.
        //
        // Credential validity cannot gate lifecycle teardown. CloudAuthManager.logout() (plan
        // 16-09) performs the "tear down unconditionally, then clear only if a credential
        // existed" sequence and reports which branch to print -- see its own javadoc for why
        // teardown must run first and the credential is read only afterward. See issue #223.
        try {
            boolean hadCredential = CloudAuthManager.logout();

            if (!hadCredential) {
                sender.sendMessage(ChatColor.YELLOW + "Not currently logged in to UltiCloud.");
                sender.sendMessage(ChatColor.GRAY + "Cloud features have been stopped regardless.");
                return;
            }

            sender.sendMessage(ChatColor.GREEN + "Successfully logged out of UltiCloud. Cloud features are now disabled.");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to re-authenticate.");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to logout: " + e.getMessage());
        }
    }

    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        CloudAuthManager.CloudStatus status = CloudAuthManager.status();
        if (status.isConnected()) {
            sender.sendMessage(ChatColor.GREEN + "UltiCloud: Connected as " + status.getUserName());
            if (status.getExpirationDate() != null) {
                sender.sendMessage(ChatColor.GRAY + "Token expires: " + status.getExpirationDate().toString());
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

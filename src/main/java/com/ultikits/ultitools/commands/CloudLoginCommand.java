package com.ultikits.ultitools.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.utils.ApiRateLimiter;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

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
        // Credential validity cannot gate lifecycle teardown. Instead this checks "is there
        // anything to clear": teardown runs unconditionally (every step of disableCloud() is
        // idempotent against "was never running"), and clearing the credential only happens when
        // one is actually stored.
        try {
            // Tear down the state machine first, then clear the credential.
            //
            // The order matters: clearing the credential alone only denies the reconnect loop a
            // valid token, it does not stop the loop -- reconnection keeps hitting the panel with
            // the now-void credential, the 401 loop keeps running, and measured behaviour shows
            // only a fresh login or a server restart actually stops it. The claim "Cloud features
            // are now disabled" was not true until this fix. See issue #223.
            PluginInitiationUtils.disableCloud();

            // The credential must be read **after** teardown. Reading it before teardown risks a
            // stale snapshot: an in-flight magic-link poll can still complete successfully in
            // between, so a "no credential before teardown" reading would send this into the
            // don't-clear branch while data.json already holds a usable token that reconnects
            // automatically on the next restart. disableCloud()'s first action is to invalidate
            // any in-flight operation, so what teardown returns to is already the final state.
            TokenEntity existing = CloudAuthManager.getCurrentToken();

            if (existing == null) {
                sender.sendMessage(ChatColor.YELLOW + "Not currently logged in to UltiCloud.");
                sender.sendMessage(ChatColor.GRAY + "Cloud features have been stopped regardless.");
                return;
            }

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

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
        // The decisions (already linked / rate limit / device-code request / polling) live behind
        // CloudAuthManager.login(DeviceLoginListener); this method only renders their outcomes.
        CloudAuthManager.login(new LoginOutput(sender));
    }

    /** Renders the device login's outcomes to the sender that ran {@code /ulticloud login}. */
    private static final class LoginOutput implements CloudAuthManager.DeviceLoginListener {

        private final CommandSender sender;

        LoginOutput(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public void onAlreadyLinked(String serverName) {
            sender.sendMessage(ChatColor.YELLOW + "This server is already linked to UltiCloud as " + serverName
                + ". Use /ulticloud logout first to link it again.");
        }

        @Override
        public void onRateLimited(long seconds) {
            sender.sendMessage(ChatColor.RED + "Please wait " + seconds + " seconds before trying again.");
        }

        @Override
        public void onRequesting() {
            sender.sendMessage(ChatColor.AQUA + "Requesting a device code from UltiCloud...");
        }

        @Override
        public void onCode(String verificationUri, String userCode, String verificationUriComplete,
                long expiresInSeconds) {
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GREEN + " To link this server to UltiCloud, open:");
            sender.sendMessage(ChatColor.AQUA + " " + verificationUri);
            sender.sendMessage(ChatColor.GREEN + " and enter the code:");
            sender.sendMessage(ChatColor.AQUA + " " + userCode);
            sender.sendMessage(ChatColor.GRAY + " Or open this link directly: " + verificationUriComplete);
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GRAY + "The code expires in " + minutes(expiresInSeconds)
                + " minutes. Waiting for confirmation...");
        }

        @Override
        public void onRequestFailed(String reason) {
            sender.sendMessage(ChatColor.RED + "Failed to get a device code: " + reason);
        }

        private static long minutes(long seconds) {
            return Math.max(1L, (seconds + 59L) / 60L);
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

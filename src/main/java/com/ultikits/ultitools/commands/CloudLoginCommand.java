package com.ultikits.ultitools.commands;

import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * Links this server to UltiCloud with a device code (protocol v2).
 *
 * Usage:
 *   /ulticloud login  — Link this server to UltiCloud
 *   /ulticloud logout — Unlink this server and revoke its credential
 *   /ulticloud status — Show link and connection status
 */
@CmdExecutor(description = "UltiCloud Authentication Commands", alias = "ulticloud", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class CloudLoginCommand extends BaseCommandExecutor {

    /**
     * Starts the device login. Deliberately <b>not</b> {@code @RunAsync} (#740): since 6.3.0 a
     * synchronous body on the primary thread runs inside the dispatch ({@code BaseCommandExecutor},
     * #541), so the first line reaches the sender before the dispatch returns -- the moment RCON sends
     * its reply. The network work runs on the login session's own executor (inside
     * {@link CloudAuthManager#login(CloudAuthManager.DeviceLoginListener)}), so this body never
     * blocks the server thread.
     *
     * @param sender the console, RCON, or another console-class sender
     */
    @CmdMapping(format = "login")
    public void login(@CmdSender CommandSender sender) {
        CloudAuthManager.login(new LoginOutput(sender));
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
        if (status.isLinked()) {
            String name = status.getServerName() != null ? status.getServerName() : "an unnamed server";
            sender.sendMessage(ChatColor.GREEN + "UltiCloud: Linked as " + name + " (server ID "
                + status.getServerId() + ")");
            sender.sendMessage(ChatColor.GRAY + "Connection: " + status.getConnectionDescription());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "UltiCloud: Not linked");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to link this server.");
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== UltiCloud Commands ===");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud login" + ChatColor.GRAY + " - Link this server to UltiCloud");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud logout" + ChatColor.GRAY
            + " - Unlink this server and revoke its credential");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY + " - Show link and connection status");
    }

    /**
     * @return {@code true} for RCON -- checked explicitly, because {@link RemoteConsoleCommandSender}
     *         is not a {@link ConsoleCommandSender} -- and for any other sender that is neither the
     *         console nor a player
     */
    private static boolean isRemoteSender(CommandSender sender) {
        return sender instanceof RemoteConsoleCommandSender
            || !(sender instanceof ConsoleCommandSender) && !(sender instanceof Player);
    }

    /**
     * Renders the device login's outcomes to the sender that ran {@code /ulticloud login}. For a
     * remote sender every line is also logged to the server console, colour codes stripped, because
     * an RCON reply is closed when its dispatch returns and the code arrives later from the login
     * session's executor; the console and players see each line once.
     */
    private static final class LoginOutput implements CloudAuthManager.DeviceLoginListener {

        private static final String REQUESTING = ChatColor.AQUA + "Requesting a device code from UltiCloud...";
        private static final String REQUESTING_REMOTE = ChatColor.AQUA
            + "Requesting a device code from UltiCloud. The code and the link will be printed in the server console.";

        private final CommandSender sender;
        private final boolean remote;

        LoginOutput(CommandSender sender) {
            this.sender = sender;
            this.remote = isRemoteSender(sender);
        }

        /** Sends {@code line}; for a remote sender also logs it to the console at INFO. */
        private void say(String line) {
            sender.sendMessage(line);
            if (remote) {
                logToConsole(line);
            }
        }

        private static void logToConsole(String line) {
            UltiTools.getInstance().getLogger().log(Level.INFO, ChatColor.stripColor(line));
        }

        @Override
        public void onAlreadyLinked(String serverName) {
            say(ChatColor.YELLOW + "This server is already linked to UltiCloud as " + serverName
                + ". Use /ulticloud logout first to link it again.");
        }

        @Override
        public void onRateLimited(long seconds) {
            say(ChatColor.RED + "Please wait " + seconds + " seconds before trying again.");
        }

        @Override
        public void onRequesting() {
            // Reached only after the already-linked and rate-limit decisions, so a remote sender is
            // told the code goes to the console only when a code is really being requested.
            if (remote) {
                sender.sendMessage(REQUESTING_REMOTE);
                logToConsole(REQUESTING);
            } else {
                sender.sendMessage(REQUESTING);
            }
        }

        @Override
        public void onCode(String verificationUri, String userCode, String verificationUriComplete,
                long expiresInSeconds) {
            say(ChatColor.GREEN + "========================================");
            say(ChatColor.GREEN + " To link this server to UltiCloud, open:");
            say(ChatColor.AQUA + " " + verificationUri);
            say(ChatColor.GREEN + " and enter the code:");
            say(ChatColor.AQUA + " " + userCode);
            say(ChatColor.GRAY + " Or open this link directly: " + verificationUriComplete);
            say(ChatColor.GREEN + "========================================");
            say(ChatColor.GRAY + "The code expires in " + minutes(expiresInSeconds)
                + " minutes. Waiting for confirmation...");
        }

        @Override
        public void onRequestFailed(String reason) {
            say(ChatColor.RED + "Failed to get a device code: " + reason);
        }

        private static long minutes(long seconds) {
            return Math.max(1L, (seconds + 59L) / 60L);
        }
    }
}

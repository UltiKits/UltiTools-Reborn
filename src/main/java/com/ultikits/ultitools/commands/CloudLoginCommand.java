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
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * Links this server to UltiCloud with a device code (protocol v2).
 *
 * Usage:
 *   /ulticloud login  — Link this server to UltiCloud
 *   /ulticloud logout — Unlink this server and revoke its credential
 *   /ulticloud status — Show link and connection status
 *   /ulticloud reconnect — Retry the UltiCloud connection now
 */
@CmdExecutor(description = "UltiCloud Authentication Commands", alias = "ulticloud", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class CloudLoginCommand extends BaseCommandExecutor {

    static final String UNLINKING_KEY = "Unlinking this server from UltiCloud...";
    static final String REVOKED_KEY = "Unlinked this server from UltiCloud and revoked its credential. Cloud features are now stopped.";
    static final String LINK_AGAIN_KEY = "Use /ulticloud login to link it again.";
    static final String REVOKE_FAILED_KEY = "Unlinked this server locally, but UltiCloud could not be reached to revoke its credential.";
    static final String REVOKE_IN_PANEL_KEY = "The cloud credential may still be valid. Revoke it in the panel: server settings > Revoke credential.";
    static final String CANCELLED_KEY = "Cancelled the pending UltiCloud link request. Cloud features are stopped.";
    static final String NOT_LINKED_KEY = "This server is not linked to UltiCloud.";
    static final String STOPPED_KEY = "Cloud features have been stopped regardless.";
    static final String RECONNECT_STARTED_KEY = "Reconnecting to UltiCloud now.";
    static final String RECONNECT_STATUS_KEY = "Use /ulticloud status to see the result.";
    static final String ALREADY_CONNECTED_KEY = "This server is already connected to UltiCloud.";
    static final String ALREADY_CONNECTING_KEY = "A connection attempt to UltiCloud is already in progress.";
    static final String UPGRADE_REQUIRED_KEY = "UltiCloud no longer accepts this UltiTools version. Upgrade UltiTools, then restart the server.";
    static final String RECONNECT_NOT_LINKED_KEY = "This server is not linked to UltiCloud. Use /ulticloud login to link it.";
    static final String RECONNECT_RATE_LIMITED_KEY = "Please wait %d seconds before trying again.";
    /**
     * Said when {@code /ulticloud reconnect} finds the connection stopped by a server-ID mismatch
     * (contract answer 21): the server is still linked, but only a restart or a new login leaves
     * that state.
     */
    static final String RECONNECT_SERVER_ID_MISMATCH_KEY = "Reconnecting stays stopped: UltiCloud answered with a "
        + "different server ID than this server is linked to. Contact UltiCloud support, or run /ulticloud logout "
        + "and then /ulticloud login.";

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
        if (refusePanelAuthentication(sender)) {
            return;
        }
        CloudAuthManager.login(new LoginOutput(sender));
    }

    @CmdMapping(format = "logout")
    public void logout(@CmdSender CommandSender sender) {
        if (refusePanelAuthentication(sender)) {
            return;
        }
        say(sender, ChatColor.AQUA, UNLINKING_KEY);
        CloudAuthManager.logoutReporting(outcome -> {
            switch (outcome) {
                case REVOKED:
                    say(sender, ChatColor.GREEN, REVOKED_KEY);
                    say(sender, ChatColor.GRAY, LINK_AGAIN_KEY);
                    break;
                case DELETED_LOCALLY_REVOKE_FAILED:
                    say(sender, ChatColor.YELLOW, REVOKE_FAILED_KEY);
                    say(sender, ChatColor.YELLOW, REVOKE_IN_PANEL_KEY);
                    say(sender, ChatColor.GRAY, LINK_AGAIN_KEY);
                    break;
                case CANCELLED_PENDING:
                    say(sender, ChatColor.GREEN, CANCELLED_KEY);
                    break;
                case NOT_LINKED:
                default:
                    say(sender, ChatColor.YELLOW, NOT_LINKED_KEY);
                    say(sender, ChatColor.GRAY, STOPPED_KEY);
            }
        });
    }

    /**
     * Backstop for synchronous aliases and nested panel commands. The dispatcher refuses direct
     * forms with command_blocked; here the console-only refusal is normal command output. Player
     * eligibility remains the existing command validator's responsibility.
     */
    private static boolean refusePanelAuthentication(CommandSender sender) {
        if (sender instanceof Player || !CommandExecutionManager.isPanelDispatch()) {
            return false;
        }
        say(sender, ChatColor.RED, "UltiCloud login/logout must be run from the server console, not the panel.");
        return true;
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

    @CmdMapping(format = "reconnect")
    public void reconnect(@CmdSender CommandSender sender) {
        CloudAuthManager.ReconnectResult result = CloudAuthManager.reconnect();
        switch (result.getKind()) {
            case STARTED:
                say(sender, ChatColor.GREEN, RECONNECT_STARTED_KEY);
                say(sender, ChatColor.GRAY, RECONNECT_STATUS_KEY);
                break;
            case ALREADY_CONNECTED:
                say(sender, ChatColor.GREEN, ALREADY_CONNECTED_KEY);
                break;
            case ALREADY_CONNECTING:
                say(sender, ChatColor.YELLOW, ALREADY_CONNECTING_KEY);
                break;
            case UPGRADE_REQUIRED:
                say(sender, ChatColor.YELLOW, UPGRADE_REQUIRED_KEY);
                break;
            case RATE_LIMITED:
                say(sender, ChatColor.RED, RECONNECT_RATE_LIMITED_KEY, result.getRemainingSeconds());
                break;
            case NOT_LINKED:
            default:
                // Still linked yet not reconnectable: the server-ID mismatch stop (plan 22-20).
                CloudAuthManager.CloudStatus status = CloudAuthManager.status();
                say(sender, ChatColor.YELLOW, status != null && status.isLinked()
                    ? RECONNECT_SERVER_ID_MISMATCH_KEY : RECONNECT_NOT_LINKED_KEY);
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== UltiCloud Commands ===");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud login" + ChatColor.GRAY + " - Link this server to UltiCloud");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud logout" + ChatColor.GRAY
            + " - Unlink this server and revoke its credential");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY + " - Show link and connection status");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud reconnect" + ChatColor.GRAY
            + " - Retry the UltiCloud connection now");
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

    private static void say(CommandSender sender, ChatColor colour, String key, Object... values) {
        sendLine(sender, colour + String.format(UltiTools.getInstance().i18n(key), values));
    }

    private static void sendLine(CommandSender sender, String line) {
        sender.sendMessage(line);
        if (isRemoteSender(sender)) {
            UltiTools.getInstance().getLogger().log(Level.INFO, ChatColor.stripColor(line));
        }
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
            sendLine(sender, line);
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

package com.ultikits.ultitools.commands;

import java.util.logging.Level;

import static com.ultikits.ultitools.commands.CoreCommandKeys.*;

import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.utils.CloudAuthManager;

/**
 * Links this server to UltiCloud with a device code (protocol v2).
 *
 * Usage:
 *   /ulticloud login  — Link this server to UltiCloud
 *   /ulticloud login &lt;code&gt; — Reattach this server with the re-pair code from the panel (as of 6.4.0)
 *   /ulticloud logout — Unlink this server and revoke its credential
 *   /ulticloud status — Show link and connection status
 *   /ulticloud reconnect — Retry the UltiCloud connection now
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
        if (refusePanelAuthentication(sender)) {
            return;
        }
        CloudAuthManager.login(new LoginOutput(sender, false));
    }

    /**
     * Reattaches this server with the single-use code the panel's "Revoke and re-pair" action shows (issue #869).
     * The code is exchanged through the same device-token poll a plain {@code /ulticloud login} uses, and the
     * credential is stored the same way; the server keeps its UltiCloud id, name and history. Like the plain form,
     * this body is not {@code @RunAsync}: the exchange runs on the login session's executor. The code is a secret
     * and is never echoed or logged; text that cannot be a code is refused here and never sent.
     *
     * @param sender the console, RCON, or another console-class sender
     * @param code   the re-pair code
     * @since 6.4.0
     */
    @CmdMapping(format = "login <code>")
    public void login(@CmdSender CommandSender sender, @CmdParam("code") String code) {
        if (refusePanelAuthentication(sender)) {
            return;
        }
        if (!CloudAuthManager.isWellFormedRepairCode(code)) {
            say(sender, ULTICLOUD_LOGIN_INVALID_CODE);
            return;
        }
        CloudAuthManager.loginWithCode(code, new LoginOutput(sender, true));
    }

    @CmdMapping(format = "logout")
    public void logout(@CmdSender CommandSender sender) {
        if (refusePanelAuthentication(sender)) {
            return;
        }
        say(sender, ULTICLOUD_LOGOUT_UNLINKING);
        CloudAuthManager.logoutReporting(outcome -> {
            switch (outcome) {
                case REVOKED:
                    say(sender, ULTICLOUD_LOGOUT_REVOKED);
                    say(sender, ULTICLOUD_LOGOUT_LINK_AGAIN);
                    break;
                case DELETED_LOCALLY_REVOKE_FAILED:
                    say(sender, ULTICLOUD_LOGOUT_REVOKE_FAILED);
                    say(sender, ULTICLOUD_LOGOUT_REVOKE_IN_PANEL);
                    say(sender, ULTICLOUD_LOGOUT_LINK_AGAIN);
                    break;
                case CANCELLED_PENDING:
                    say(sender, ULTICLOUD_LOGOUT_CANCELLED);
                    break;
                case LOCAL_DELETE_FAILED:
                    say(sender, ULTICLOUD_LOGOUT_LOCAL_DELETE_FAILED);
                    say(sender, ULTICLOUD_LOGOUT_STOPPED);
                    break;
                case NOT_LINKED:
                default:
                    say(sender, ULTICLOUD_LOGOUT_NOT_LINKED);
                    say(sender, ULTICLOUD_LOGOUT_STOPPED);
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
        say(sender, ULTICLOUD_PANEL_REFUSED);
        return true;
    }

    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        CloudAuthManager.CloudStatus status = CloudAuthManager.status();
        if (status.isLinked()) {
            Arg name = status.getServerName() != null
                    ? Arg.string("name", status.getServerName())
                    : Arg.text("name", Text.of(ULTICLOUD_STATUS_UNNAMED));
            CommandRuntime.messages().send(sender, ULTICLOUD_STATUS_LINKED, name,
                    Arg.string("server_id", String.valueOf(status.getServerId())));
            CommandRuntime.messages().send(sender, ULTICLOUD_STATUS_CONNECTION,
                    Arg.string("connection", String.valueOf(status.getConnectionDescription())));
        } else {
            CommandRuntime.messages().send(sender, ULTICLOUD_STATUS_NOT_LINKED);
            CommandRuntime.messages().send(sender, ULTICLOUD_STATUS_LOGIN_HINT);
        }
    }

    @CmdMapping(format = "reconnect")
    public void reconnect(@CmdSender CommandSender sender) {
        CloudAuthManager.ReconnectResult result = CloudAuthManager.reconnect();
        switch (result.getKind()) {
            case STARTED:
                say(sender, ULTICLOUD_RECONNECT_STARTED);
                say(sender, ULTICLOUD_RECONNECT_STATUS_HINT);
                break;
            case ALREADY_CONNECTED:
                say(sender, ULTICLOUD_RECONNECT_ALREADY_CONNECTED);
                break;
            case ALREADY_CONNECTING:
                say(sender, ULTICLOUD_RECONNECT_ALREADY_CONNECTING);
                break;
            case UPGRADE_REQUIRED:
                say(sender, ULTICLOUD_RECONNECT_UPGRADE_REQUIRED);
                break;
            case RATE_LIMITED:
                say(sender, ULTICLOUD_RATE_LIMITED, Arg.number("seconds", result.getRemainingSeconds()));
                break;
            case NOT_LINKED:
            default:
                // Still linked yet not reconnectable: the server-ID mismatch stop (plan 22-20).
                CloudAuthManager.CloudStatus status = CloudAuthManager.status();
                say(sender, status != null && status.isLinked()
                    ? ULTICLOUD_RECONNECT_SERVER_ID_MISMATCH : ULTICLOUD_RECONNECT_NOT_LINKED);
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_HEADER);
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_LOGIN);
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_LOGIN_CODE);
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_LOGOUT);
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_STATUS);
        CommandRuntime.messages().send(sender, ULTICLOUD_HELP_RECONNECT);
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

    private static void say(CommandSender sender, MessageKey key, Arg... args) {
        Component line = CommandRuntime.messages().render(key, sender, args);
        sender.sendMessage(line);
        if (isRemoteSender(sender)) {
            UltiTools.getInstance().getLogger().log(Level.INFO, PlainTextComponentSerializer.plainText().serialize(line));
        }
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
        private static final String REDEEMING = ChatColor.AQUA + "Redeeming the re-pair code with UltiCloud...";
        private static final String REDEEMING_REMOTE = ChatColor.AQUA
            + "Redeeming the re-pair code with UltiCloud. The result will be printed in the server console.";

        private final CommandSender sender;
        private final boolean remote;
        /** {@code true} for {@code /ulticloud login <code>}. */
        private final boolean withCode;

        LoginOutput(CommandSender sender, boolean withCode) {
            this.sender = sender;
            this.remote = isRemoteSender(sender);
            this.withCode = withCode;
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
            String line = withCode ? REDEEMING : REQUESTING;
            if (remote) {
                sender.sendMessage(withCode ? REDEEMING_REMOTE : REQUESTING_REMOTE);
                logToConsole(line);
            } else {
                sender.sendMessage(line);
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
            say(ChatColor.RED + (withCode ? "Could not use the re-pair code: " : "Failed to get a device code: ")
                + reason);
        }

        private static long minutes(long seconds) {
            return Math.max(1L, (seconds + 59L) / 60L);
        }
    }
}

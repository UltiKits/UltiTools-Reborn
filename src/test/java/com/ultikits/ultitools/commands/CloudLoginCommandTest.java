package com.ultikits.ultitools.commands;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * {@code /ulticloud login}, {@code status} and the help text, rendered byte for byte (WR-08), for the
 * protocol v2 device login (plan 22-01).
 * <p>
 * {@code CloudAuthManager} is mocked statically and each {@link CloudAuthManager.DeviceLoginListener}
 * outcome is driven directly. A remote sender (RCON, or any sender that is neither the console nor a
 * player) also gets every line logged to the server console at INFO, colour codes stripped, because
 * its reply may already be closed when the code arrives (#740); the console and players do not.
 */
@DisplayName("ulticloud login / status / help: rendered strings, byte for byte")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CloudLoginCommandTest {

    private static final String BORDER = ChatColor.GREEN + "========================================";
    private static final String[] CODE_BLOCK = {
        BORDER,
        ChatColor.GREEN + " To link this server to UltiCloud, open:",
        ChatColor.AQUA + " https://panel.ultikits.com/device",
        ChatColor.GREEN + " and enter the code:",
        ChatColor.AQUA + " BCDF-GHJK",
        ChatColor.GRAY + " Or open this link directly: https://panel.ultikits.com/device?code=BCDF-GHJK",
        BORDER,
        ChatColor.GRAY + "The code expires in 10 minutes. Waiting for confirmation...",
    };

    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> lenient().when(ultiTools.getLogger()).thenReturn(logger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the UltiTools singleton mocked in setUp
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /** Runs {@code login(sender)} with {@code outcome} applied to the listener the command passes. */
    private static void login(CommandSender sender, Consumer<CloudAuthManager.DeviceLoginListener> outcome) {
        try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
            auth.when(() -> CloudAuthManager.login(any(CloudAuthManager.DeviceLoginListener.class)))
                    .thenAnswer(invocation -> {
                        outcome.accept(invocation.getArgument(0));
                        return null;
                    });
            new CloudLoginCommand().login(sender);
        }
    }

    private static void code(CloudAuthManager.DeviceLoginListener listener) {
        listener.onCode("https://panel.ultikits.com/device", "BCDF-GHJK",
                "https://panel.ultikits.com/device?code=BCDF-GHJK", 600);
    }

    @Nested
    @DisplayName("login from the console")
    class ConsoleSender {

        @Test
        @DisplayName("already linked: one yellow line naming the server and /ulticloud logout")
        void alreadyLinked() {
            ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

            login(sender, listener -> listener.onAlreadyLinked("Survival"));

            verify(sender).sendMessage(ChatColor.YELLOW
                    + "This server is already linked to UltiCloud as Survival. Use /ulticloud logout first to link it again.");
        }

        @Test
        @DisplayName("rate limited: one red line with the remaining seconds")
        void rateLimited() {
            ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

            login(sender, listener -> listener.onRateLimited(42L));

            verify(sender).sendMessage(ChatColor.RED + "Please wait 42 seconds before trying again.");
        }

        @Test
        @DisplayName("requesting: one aqua line")
        void requesting() {
            ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

            login(sender, CloudAuthManager.DeviceLoginListener::onRequesting);

            verify(sender).sendMessage(ChatColor.AQUA + "Requesting a device code from UltiCloud...");
        }

        @Test
        @DisplayName("code issued: the eight-line block in order, sent once and not duplicated into the log")
        void codeBlockIsSentOnceInOrder() {
            ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

            login(sender, CloudLoginCommandTest::code);

            InOrder order = inOrder(sender);
            for (String line : CODE_BLOCK) {
                order.verify(sender).sendMessage(line);
            }
            verify(sender, times(2)).sendMessage(BORDER);
            verify(logger, never()).log(any(Level.class), anyString());
        }

        @Test
        @DisplayName("request failed: one red line with the reason")
        void requestFailed() {
            ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

            login(sender, listener -> listener.onRequestFailed("API URL not configured"));

            verify(sender).sendMessage(ChatColor.RED + "Failed to get a device code: API URL not configured");
        }
    }

    @Nested
    @DisplayName("login from a remote sender (#740)")
    class RemoteSender {

        @Test
        @DisplayName("RCON: requesting sends the console notice and logs the plain requesting line")
        void rconRequestingSendsTheConsoleNotice() {
            RemoteConsoleCommandSender rcon = mock(RemoteConsoleCommandSender.class);

            login(rcon, CloudAuthManager.DeviceLoginListener::onRequesting);

            verify(rcon).sendMessage(ChatColor.AQUA
                    + "Requesting a device code from UltiCloud. The code and the link will be printed in the server console.");
            verify(logger).log(Level.INFO, "Requesting a device code from UltiCloud...");
        }

        @Test
        @DisplayName("RCON: every line of the code block reaches the sender and the console log, colours stripped")
        void rconCodeBlockIsAlsoLogged() {
            RemoteConsoleCommandSender rcon = mock(RemoteConsoleCommandSender.class);

            login(rcon, CloudLoginCommandTest::code);

            InOrder order = inOrder(rcon);
            for (String line : CODE_BLOCK) {
                order.verify(rcon).sendMessage(line);
            }
            InOrder logged = inOrder(logger);
            for (String line : CODE_BLOCK) {
                logged.verify(logger).log(Level.INFO, ChatColor.stripColor(line));
            }
        }

        @Test
        @DisplayName("any other non-console, non-player sender is treated like RCON")
        void otherSenderIsRemote() {
            CommandSender other = mock(CommandSender.class);

            login(other, listener -> listener.onRequestFailed("UltiCloud could not be reached"));

            verify(other).sendMessage(ChatColor.RED + "Failed to get a device code: UltiCloud could not be reached");
            verify(logger).log(Level.INFO, "Failed to get a device code: UltiCloud could not be reached");
        }
    }

    @Nested
    @DisplayName("login from a player")
    class PlayerSender {

        @Test
        @DisplayName("a player gets the lines and nothing is logged")
        void playerIsUnchanged() {
            Player player = mock(Player.class);

            login(player, CloudLoginCommandTest::code);

            for (String line : CODE_BLOCK) {
                verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(line);
            }
            verify(logger, never()).log(any(Level.class), anyString());
        }
    }

    @Nested
    @DisplayName("status")
    class StatusBranches {

        @ParameterizedTest
        @ValueSource(strings = {"connected", "connecting", "reconnecting in 5 s (attempt 2)",
            "stopped — this UltiTools version is no longer accepted; upgrade UltiTools",
            "stopped — another connection is using this server's credential",
            "paused — plan limit reached; retrying every 15 minutes (next in 15 min). Run /ulticloud reconnect after renewing.",
            "not connected"})
        @DisplayName("linked: link details then the connection description")
        void linked(String description) {
            CommandSender sender = mock(CommandSender.class);
            CloudAuthManager.CloudStatus status = buildStatus(true, "Survival", "01JTESTSERVERID0000000000");
            lenient().when(status.getConnectionDescription()).thenReturn(description);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::status).thenReturn(status);

                new CloudLoginCommand().status(sender);
            }

            InOrder order = inOrder(sender);
            order.verify(sender).sendMessage(ChatColor.GREEN + "UltiCloud: Linked as Survival (server ID 01JTESTSERVERID0000000000)");
            order.verify(sender).sendMessage(ChatColor.GRAY + "Connection: " + description);
            verify(sender, times(2)).sendMessage(anyString());
        }

        @Test
        @DisplayName("not linked: yellow 'Not linked' then the gray login hint")
        void notLinked() {
            CommandSender sender = mock(CommandSender.class);
            CloudAuthManager.CloudStatus status = buildStatus(false, null, null);

            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::status).thenReturn(status);

                new CloudLoginCommand().status(sender);
            }

            InOrder order = inOrder(sender);
            order.verify(sender).sendMessage(ChatColor.YELLOW + "UltiCloud: Not linked");
            order.verify(sender).sendMessage(ChatColor.GRAY + "Use /ulticloud login to link this server.");
        }

        private CloudAuthManager.CloudStatus buildStatus(boolean linked, String serverName, String serverId) {
            CloudAuthManager.CloudStatus status = mock(CloudAuthManager.CloudStatus.class);
            lenient().when(status.isLinked()).thenReturn(linked);
            lenient().when(status.getServerName()).thenReturn(serverName);
            lenient().when(status.getServerId()).thenReturn(serverId);
            return status;
        }
    }

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("the header and the four subcommand lines")
        void helpLines() {
            CommandSender sender = mock(CommandSender.class);

            new CloudLoginCommand().handleHelp(sender);

            InOrder order = inOrder(sender);
            order.verify(sender).sendMessage(ChatColor.AQUA + "=== UltiCloud Commands ===");
            order.verify(sender).sendMessage(ChatColor.WHITE + "/ulticloud login" + ChatColor.GRAY
                    + " - Link this server to UltiCloud");
            order.verify(sender).sendMessage(ChatColor.WHITE + "/ulticloud logout" + ChatColor.GRAY
                    + " - Unlink this server and revoke its credential");
            order.verify(sender).sendMessage(ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY
                    + " - Show link and connection status");
            // Pinned by the plan-22-06 verification: without this line, deleting the
            // "/ulticloud reconnect" help sentence left the whole suite green.
            order.verify(sender).sendMessage(ChatColor.WHITE + "/ulticloud reconnect" + ChatColor.GRAY
                    + " - Retry the UltiCloud connection now");
            order.verifyNoMoreInteractions();
        }
    }
}

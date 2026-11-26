package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertColour;
import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertLines;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.utils.CloudAuthManager;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudCommandMessagesTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void helpHasExactlySixLinesAndDoesNotLogRemoteReplies(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            CommandSender sender = mock(RemoteConsoleCommandSender.class);
            new CloudLoginCommand().handleHelp(sender);
            var lines = assertLines(sender, "=== UltiCloud Commands ===",
                    "/ulticloud login - Link this server to UltiCloud",
                    "/ulticloud login <code> - Reattach this server with the re-pair code from the panel",
                    "/ulticloud logout - Unlink this server and revoke its credential",
                    "/ulticloud status - Show link and connection status",
                    "/ulticloud reconnect - Retry the UltiCloud connection now");
            assertColour(lines.getFirst(), NamedTextColor.AQUA);
            lines.subList(1, 6).forEach(line -> assertColour(line, NamedTextColor.WHITE));
            verifyNoInteractions(fixture.logger);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void statusKeepsTwoLinesIncludingUnnamedServerAndNeverLogs(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language); var auth = mockStatic(CloudAuthManager.class)) {
            var status = mock(CloudAuthManager.CloudStatus.class);
            auth.when(CloudAuthManager::status).thenReturn(status);
            CommandSender sender = mock(RemoteConsoleCommandSender.class);
            new CloudLoginCommand().status(sender);
            var unlinked = assertLines(sender, "UltiCloud: Not linked", "Use /ulticloud login to link this server.");
            assertColour(unlinked.getFirst(), NamedTextColor.YELLOW);
            assertColour(unlinked.getLast(), NamedTextColor.GRAY);
            for (String name : new String[]{null, "<red>Survival"}) {
                clearInvocations(sender);
                when(status.isLinked()).thenReturn(true);
                when(status.getServerName()).thenReturn(name);
                when(status.getServerId()).thenReturn("<red>server-id");
                when(status.getConnectionDescription()).thenReturn("<red>connected");
                new CloudLoginCommand().status(sender);
                var linked = assertLines(sender, "UltiCloud: Linked as " + (name == null ? "an unnamed server" : name)
                        + " (server ID <red>server-id)", "Connection: <red>connected");
                assertColour(linked.getFirst(), NamedTextColor.GREEN);
                assertColour(linked.getLast(), NamedTextColor.GRAY);
            }
            verifyNoInteractions(fixture.logger);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void invalidRepairCodeHasOneRedReplyAndDoesNotStartLogin(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language); var auth = mockStatic(CloudAuthManager.class)) {
            CommandSender sender = mock(RemoteConsoleCommandSender.class);
            new CloudLoginCommand().login(sender, "not-a-code");
            String expected = "zh".equals(language)
                    ? "这不是有效的 UltiCloud 重新配对代码。请从面板复制完整的代码后重试。"
                    : "That is not a valid UltiCloud re-pair code. Copy the whole code from the panel and try again.";
            assertColour(assertLines(sender, expected).getFirst(), NamedTextColor.RED);
            verify(fixture.logger).log(java.util.logging.Level.INFO, expected);
            auth.verify(() -> CloudAuthManager.loginWithCode(anyString(), any()), never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void panelRefusalPreservesAllAuthenticationGuards(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language);
                var dispatch = mockStatic(CommandExecutionManager.class); var auth = mockStatic(CloudAuthManager.class)) {
            dispatch.when(CommandExecutionManager::isPanelDispatch).thenReturn(true);
            CommandSender sender = mock(CommandSender.class);
            CloudLoginCommand command = new CloudLoginCommand();
            command.login(sender);
            command.login(sender, "not-a-code");
            command.logout(sender);
            String expected = "UltiCloud login/logout must be run from the server console, not the panel.";
            assertLines(sender, expected, expected, expected).forEach(line -> assertColour(line, NamedTextColor.RED));
            auth.verifyNoInteractions();
        }
    }
}

package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertColour;
import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult.Kind;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudReconnectCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void everyOutcomeUsesTheLanguageCatalogueAndMirrorsRemoteReplies(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (Kind kind : Kind.values()) {
                for (Class<? extends CommandSender> type : Arrays.asList(ConsoleCommandSender.class,
                        RemoteConsoleCommandSender.class, CommandSender.class)) {
                    clearInvocations(fixture.logger);
                    CommandSender sender = mock(type);
                    ReconnectResult result = result(kind);
                    try (var auth = mockStatic(CloudAuthManager.class)) {
                        auth.when(CloudAuthManager::reconnect).thenReturn(result);
                        new CloudLoginCommand().reconnect(sender);
                        auth.verify(CloudAuthManager::reconnect);
                    }
                    String[] expected = expected(language, kind);
                    var lines = assertLines(sender, expected);
                    assertColour(lines.getFirst(), switch (kind) {
                        case STARTED, ALREADY_CONNECTED -> NamedTextColor.GREEN;
                        case RATE_LIMITED -> NamedTextColor.RED;
                        default -> NamedTextColor.YELLOW;
                    });
                    if (lines.size() == 2) {
                        assertColour(lines.getLast(), NamedTextColor.GRAY);
                    }
                    if (ConsoleCommandSender.class.equals(type)) {
                        verifyNoInteractions(fixture.logger);
                    } else {
                        var logged = inOrder(fixture.logger);
                        for (String line : expected) {
                            logged.verify(fixture.logger).log(Level.INFO, line);
                            assertThat(line).doesNotContain("§");
                        }
                        logged.verifyNoMoreInteractions();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void linkedButNotReconnectableExplainsServerIdMismatch(String language) {
        try (var fixture = new CoreCommandMessageTestSupport(language); var auth = mockStatic(CloudAuthManager.class)) {
            ReconnectResult result = result(Kind.NOT_LINKED);
            var status = mock(CloudAuthManager.CloudStatus.class);
            when(status.isLinked()).thenReturn(true);
            auth.when(CloudAuthManager::status).thenReturn(status);
            auth.when(CloudAuthManager::reconnect).thenReturn(result);
            CommandSender sender = mock(ConsoleCommandSender.class);
            new CloudLoginCommand().reconnect(sender);
            assertColour(assertLines(sender, "zh".equals(language)
                    ? "重连保持停止：UltiCloud 应答的服务器 ID 与本服务器绑定的不同。请联系 UltiCloud 支持，或先执行 /ulticloud logout 再执行 /ulticloud login。"
                    : "Reconnecting stays stopped: UltiCloud answered with a different server ID than this server is linked to. Contact UltiCloud support, or run /ulticloud logout and then /ulticloud login.")
                    .getFirst(), NamedTextColor.YELLOW);
        }
    }

    @Test
    @ExtendWith(CommandTestExtension.class)
    void rconGetsTheReplyBeforeTheRealDispatchReturns() {
        try (var fixture = new CoreCommandMessageTestSupport("zh");
                var bukkit = mockStatic(Bukkit.class); var auth = mockStatic(CloudAuthManager.class)) {
            RemoteConsoleCommandSender sender = mock(RemoteConsoleCommandSender.class);
            when(sender.isOp()).thenReturn(true);
            ReconnectResult result = result(Kind.STARTED);
            auth.when(CloudAuthManager::reconnect).thenReturn(result);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            com.ultikits.ultitools.command.engine.CommandTestSupport.dispatch(new CloudLoginCommand(), sender,
                    mock(Command.class), "ulticloud", new String[]{"reconnect"});
            assertLines(sender, expected("zh", Kind.STARTED));
            auth.verify(CloudAuthManager::reconnect);
        }
    }

    @Test
    void reconnectKeepsConsoleOpRestrictionsAndHasNoAsyncAnnotation() throws Exception {
        Method method = CloudLoginCommand.class.getMethod("reconnect", CommandSender.class);
        assertThat(method.getAnnotation(CmdMapping.class).format()).isEqualTo("reconnect");
        assertThat(method.isAnnotationPresent(RunAsync.class)).isFalse();
        assertThat(method.isAnnotationPresent(AsyncCommand.class)).isFalse();
        assertThat(CloudLoginCommand.class.getAnnotation(CmdExecutor.class).requireOp()).isTrue();
        assertThat(CloudLoginCommand.class.getAnnotation(CmdTarget.class).value()).isEqualTo(CmdTarget.CmdTargetType.CONSOLE);
    }

    private static ReconnectResult result(Kind kind) {
        ReconnectResult result = mock(ReconnectResult.class);
        when(result.getKind()).thenReturn(kind);
        lenient().when(result.getRemainingSeconds()).thenReturn(7L);
        return result;
    }

    private static String[] expected(String language, Kind kind) {
        boolean zh = "zh".equals(language);
        return switch (kind) {
            case STARTED -> new String[]{zh ? "正在重新连接 UltiCloud。" : "Reconnecting to UltiCloud now.",
                    zh ? "使用 /ulticloud status 查看结果。" : "Use /ulticloud status to see the result."};
            case ALREADY_CONNECTED -> new String[]{zh ? "此服务器已连接到 UltiCloud。" : "This server is already connected to UltiCloud."};
            case ALREADY_CONNECTING -> new String[]{zh ? "正在连接 UltiCloud，请稍候。" : "A connection attempt to UltiCloud is already in progress."};
            case UPGRADE_REQUIRED -> new String[]{zh ? "UltiCloud 不再接受此版本的 UltiTools。请升级 UltiTools 后重启服务器。"
                    : "UltiCloud no longer accepts this UltiTools version. Upgrade UltiTools, then restart the server."};
            case NOT_LINKED -> new String[]{zh ? "此服务器尚未绑定 UltiCloud。请使用 /ulticloud login 绑定。"
                    : "This server is not linked to UltiCloud. Use /ulticloud login to link it."};
            case RATE_LIMITED -> new String[]{zh ? "请等待 7 秒后再试。" : "Please wait 7 seconds before trying again."};
        };
    }
}

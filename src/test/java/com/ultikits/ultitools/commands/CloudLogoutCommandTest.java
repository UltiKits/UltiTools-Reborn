package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertColour;
import static com.ultikits.ultitools.commands.CoreCommandMessageTestSupport.assertLines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.CloudAuthManager.LogoutOutcome;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudLogoutCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void exactOutcomeLinesAndRemoteLogging(String language) {
        boolean zh = "zh".equals(language);
        try (var fixture = new CoreCommandMessageTestSupport(language)) {
            for (LogoutOutcome outcome : LogoutOutcome.values()) {
                for (Class<? extends CommandSender> type : Arrays.asList(ConsoleCommandSender.class,
                        RemoteConsoleCommandSender.class, CommandSender.class)) {
                    clearInvocations(fixture.logger);
                    CommandSender sender = mock(type);
                    try (var auth = mockStatic(CloudAuthManager.class)) {
                        auth.when(() -> CloudAuthManager.logoutReporting(any())).thenAnswer(call -> {
                            assertThat(CoreCommandMessageTestSupport.components(sender, 1)).hasSize(1);
                            Consumer<LogoutOutcome> callback = call.getArgument(0);
                            callback.accept(outcome);
                            return null;
                        });
                        new CloudLoginCommand().logout(sender);
                        auth.verify(() -> CloudAuthManager.logoutReporting(any()));
                    }
                    String[] expected = expected(zh, outcome);
                    var lines = assertLines(sender, expected);
                    assertColour(lines.getFirst(), NamedTextColor.AQUA);
                    assertColour(lines.get(1), switch (outcome) {
                        case REVOKED, CANCELLED_PENDING -> NamedTextColor.GREEN;
                        case LOCAL_DELETE_FAILED -> NamedTextColor.RED;
                        default -> NamedTextColor.YELLOW;
                    });
                    if (lines.size() > 2) {
                        assertColour(lines.getLast(), NamedTextColor.GRAY);
                    }
                    if (type == ConsoleCommandSender.class) {
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

    private static String[] expected(boolean zh, LogoutOutcome outcome) {
        String first = zh ? "正在解除此服务器与 UltiCloud 的绑定……" : "Unlinking this server from UltiCloud...";
        String again = zh ? "使用 /ulticloud login 重新绑定。" : "Use /ulticloud login to link it again.";
        String stopped = zh ? "云功能仍已停止。" : "Cloud features have been stopped regardless.";
        return switch (outcome) {
            case REVOKED -> new String[]{first, zh ? "已解除此服务器与 UltiCloud 的绑定并吊销其凭据。云功能现已停止。"
                    : "Unlinked this server from UltiCloud and revoked its credential. Cloud features are now stopped.", again};
            case DELETED_LOCALLY_REVOKE_FAILED -> new String[]{first,
                    zh ? "已在本地解除此服务器的绑定，但无法联系 UltiCloud 吊销其凭据。"
                            : "Unlinked this server locally, but UltiCloud could not be reached to revoke its credential.",
                    zh ? "云端凭据可能仍然有效。请在面板的服务器设置 > 吊销凭据中将其吊销。"
                            : "The cloud credential may still be valid. Revoke it in the panel: server settings > Revoke credential.", again};
            case CANCELLED_PENDING -> new String[]{first, zh ? "已取消等待中的 UltiCloud 绑定请求。云功能已停止。"
                    : "Cancelled the pending UltiCloud link request. Cloud features are stopped."};
            case LOCAL_DELETE_FAILED -> new String[]{first, zh
                    ? "无法从本地凭据文件中删除此服务器的 UltiCloud 凭据。请查看服务器日志，然后再次执行 /ulticloud logout。"
                    : "Could not delete this server's UltiCloud credential from the local credentials file. Check the server log, then run /ulticloud logout again.", stopped};
            default -> new String[]{first, zh ? "此服务器尚未绑定 UltiCloud。" : "This server is not linked to UltiCloud.", stopped};
        };
    }
}

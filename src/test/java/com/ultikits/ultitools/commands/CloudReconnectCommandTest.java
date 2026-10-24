package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult;
import com.ultikits.ultitools.utils.CloudAuthManager.ReconnectResult.Kind;
import com.ultikits.ultitools.utils.TestHelper;

class CloudReconnectCommandTest {
    private Logger logger;
    private Object previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = UltiTools.getInstance();
        logger = mock(Logger.class);
        TestHelper.mockUltiToolsInstance(plugin -> lenient().when(plugin.getLogger()).thenReturn(logger));
    }

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    void tearDown() throws Exception {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, previousPlugin);
    }

    @ParameterizedTest
    @CsvSource({"en,STARTED", "en,ALREADY_CONNECTED", "en,ALREADY_CONNECTING", "en,UPGRADE_REQUIRED", "en,NOT_LINKED",
        "en,RATE_LIMITED", "zh,STARTED", "zh,ALREADY_CONNECTED", "zh,ALREADY_CONNECTING", "zh,UPGRADE_REQUIRED",
        "zh,NOT_LINKED", "zh,RATE_LIMITED"})
    void everyOutcomeUsesTheLanguageCatalogueAndMirrorsRemoteReplies(String language, Kind kind) throws Exception {
        JsonObject catalogue = JsonParser.parseString(new String(Files.readAllBytes(
                Paths.get("src/main/resources/lang/" + language + ".json")), StandardCharsets.UTF_8)).getAsJsonObject();
        when(UltiTools.getInstance().i18n(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            assertThat(catalogue.has(key)).as("catalogue contains %s", key).isTrue();
            return catalogue.get(key).getAsString();
        });
        for (Class<? extends CommandSender> type : Arrays.asList(ConsoleCommandSender.class,
                RemoteConsoleCommandSender.class, CommandSender.class)) {
            org.mockito.Mockito.clearInvocations(logger);
            CommandSender sender = mock(type);
            List<String> lines = new ArrayList<>();
            doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
            // Built before the static mock is opened: the helper stubs its own mock, and Mockito
            // rejects a nested stubbing inside an unfinished one.
            ReconnectResult stubbed = result(kind);
            try (MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
                auth.when(CloudAuthManager::reconnect).thenReturn(stubbed);
                new CloudLoginCommand().reconnect(sender);
                auth.verify(CloudAuthManager::reconnect);
            }
            assertThat(lines).containsExactly(expected(language, kind));
            if (type == ConsoleCommandSender.class) {
                verifyNoInteractions(logger);
            } else {
                InOrder order = inOrder(logger);
                for (String line : lines) { order.verify(logger).log(Level.INFO, ChatColor.stripColor(line)); }
                order.verifyNoMoreInteractions();
            }
        }
    }

    @Test
    void rconGetsTheReplyBeforeTheRealDispatchReturns() {
        RemoteConsoleCommandSender rcon = mock(RemoteConsoleCommandSender.class);
        when(rcon.isOp()).thenReturn(true);
        Command command = mock(Command.class);
        List<String> lines = new ArrayList<>();
        doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(rcon).sendMessage(anyString());
        ReconnectResult stubbed = result(Kind.STARTED);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<CloudAuthManager> auth = mockStatic(CloudAuthManager.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            auth.when(CloudAuthManager::reconnect).thenReturn(stubbed);
            new CloudLoginCommand().onCommand(rcon, command, "ulticloud", new String[]{"reconnect"});
            assertThat(lines).containsExactly(expected("en", Kind.STARTED));
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

    @Test
    void helpListsReconnectImmediatelyAfterStatus() {
        CommandSender sender = mock(CommandSender.class);
        List<String> lines = new ArrayList<>();
        doAnswer(call -> { lines.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
        new CloudLoginCommand().handleHelp(sender);
        assertThat(lines).hasSize(5);
        assertThat(lines.subList(3, 5)).containsExactly(
                ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY + " - Show link and connection status",
                ChatColor.WHITE + "/ulticloud reconnect" + ChatColor.GRAY + " - Retry the UltiCloud connection now");
    }

    private static ReconnectResult result(Kind kind) {
        ReconnectResult result = mock(ReconnectResult.class);
        when(result.getKind()).thenReturn(kind);
        lenient().when(result.getRemainingSeconds()).thenReturn(7L);
        return result;
    }

    private static String[] expected(String language, Kind kind) {
        boolean zh = "zh".equals(language);
        switch (kind) {
            case STARTED:
                return new String[]{ChatColor.GREEN + (zh ? "正在重新连接 UltiCloud。" : "Reconnecting to UltiCloud now."),
                    ChatColor.GRAY + (zh ? "使用 /ulticloud status 查看结果。" : "Use /ulticloud status to see the result.")};
            case ALREADY_CONNECTED:
                return new String[]{ChatColor.GREEN + (zh ? "此服务器已连接到 UltiCloud。" : "This server is already connected to UltiCloud.")};
            case ALREADY_CONNECTING:
                return new String[]{ChatColor.YELLOW + (zh ? "正在连接 UltiCloud，请稍候。" : "A connection attempt to UltiCloud is already in progress.")};
            case UPGRADE_REQUIRED:
                return new String[]{ChatColor.YELLOW + (zh ? "UltiCloud 不再接受此版本的 UltiTools。请升级 UltiTools 后重启服务器。"
                        : "UltiCloud no longer accepts this UltiTools version. Upgrade UltiTools, then restart the server.")};
            case NOT_LINKED:
                return new String[]{ChatColor.YELLOW + (zh ? "此服务器尚未绑定 UltiCloud。请使用 /ulticloud login 绑定。"
                        : "This server is not linked to UltiCloud. Use /ulticloud login to link it.")};
            case RATE_LIMITED:
                return new String[]{ChatColor.RED + (zh ? "请等待 7 秒后再试。" : "Please wait 7 seconds before trying again.")};
            default:
                throw new AssertionError(kind);
        }
    }
}

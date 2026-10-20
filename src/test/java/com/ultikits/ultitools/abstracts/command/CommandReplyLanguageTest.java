package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.entities.Language;

/**
 * #556: the reply every sender gets when a command body throws, and the default processing notice of
 * an async command, follow the server's {@code language}. Before the fix both were Chinese literals
 * that never went through the catalogue, so an English server answered in Chinese.
 * <p>
 * The {@code UltiTools} mock resolves {@code i18n} against the real {@code lang/*.json} shipped in
 * the jar, exactly as the running framework does.
 */
@DisplayName("Command failure reply and processing notice follow the language (#556)")
class CommandReplyLanguageTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"replylang"})
    static class ThrowingExecutor extends BaseCommandExecutor {
        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "boom")
        public void boom(CommandSender sender) {
            throw new IllegalStateException("the real reason");
        }

        @AsyncCommand(showProcessing = true, timeout = 0)
        @CmdMapping(format = "slow")
        public void slow(CommandSender sender) {
            // The body never runs here: the scheduler below captures tasks and runs none of them.
        }
    }

    private void useLanguage(String code) {
        Language language = new Language(new File("src/main/resources/lang/" + code + ".json"));
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString()))
                .thenAnswer(inv -> language.getLocalizedText(inv.getArgument(0)));
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenReturn(null);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);
        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("replylang");
    }

    @BeforeEach
    void noStaticsYet() {
        ultiToolsMock = null;
        bukkitMock = null;
    }

    @AfterEach
    void tearDown() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    private List<String> messagesTo(CommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static boolean hasCjk(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.HAN.equals(Character.UnicodeScript.of(cp)));
    }

    @Test
    @DisplayName("language: en -- a throwing command body is answered in English, carrying the real reason")
    void englishFailureReply() {
        useLanguage("en");
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

        new ThrowingExecutor().onCommand(sender, command, "replylang", new String[]{"boom"});

        List<String> messages = messagesTo(sender);
        assertThat(messages).anyMatch(m -> m.contains("Command execution failed") && m.contains("the real reason"));
        assertThat(messages).noneMatch(CommandReplyLanguageTest::hasCjk);
    }

    @Test
    @DisplayName("language: zh -- the reply is the existing Chinese text, unchanged")
    void chineseFailureReply() {
        useLanguage("zh");
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

        new ThrowingExecutor().onCommand(sender, command, "replylang", new String[]{"boom"});

        assertThat(messagesTo(sender)).anyMatch(m -> m.contains("命令执行出错: the real reason"));
    }

    @Test
    @DisplayName("language: en -- the async processing notice is English")
    void englishProcessingNotice() {
        useLanguage("en");
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

        new ThrowingExecutor().onCommand(sender, command, "replylang", new String[]{"slow"});

        List<String> messages = messagesTo(sender);
        assertThat(messages).anyMatch(m -> m.contains("Processing..."));
        assertThat(messages).noneMatch(CommandReplyLanguageTest::hasCjk);
    }

    @Test
    @DisplayName("language: zh -- the async processing notice is the existing Chinese text")
    void chineseProcessingNotice() {
        useLanguage("zh");
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);

        new ThrowingExecutor().onCommand(sender, command, "replylang", new String[]{"slow"});

        assertThat(messagesTo(sender)).anyMatch(m -> m.contains("处理中..."));
    }
}

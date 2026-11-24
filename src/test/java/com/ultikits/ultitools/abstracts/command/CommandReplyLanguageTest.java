package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.Text;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** The existing failure/processing language regression, now exercised against the real text engine. */
class CommandReplyLanguageTest {
    private CommandMessageTestSupport services;
    private AutoCloseable runtime;
    private MockedStatic<Bukkit> bukkit;

    private void useLanguage(String code) {
        services = new CommandMessageTestSupport(code);
        Scheduler scheduler = mock(Scheduler.class);
        TaskContext global = mock(TaskContext.class);
        TaskContext async = mock(TaskContext.class);
        when(scheduler.global()).thenReturn(global);
        when(scheduler.async()).thenReturn(async);
        when(global.isCurrentThread()).thenReturn(true);
        runtime = CommandRuntime.override(services.messages(), scheduler);
        BukkitScheduler legacyScheduler = mock(BukkitScheduler.class);
        when(legacyScheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenReturn(null);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getScheduler).thenReturn(legacyScheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.close();
            services.close();
            bukkit.close();
        }
    }

    private static List<Component> componentsTo(CommandSender sender) {
        var captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    private static List<String> messagesTo(CommandSender sender) {
        return componentsTo(sender).stream().map(CommandMessageTestSupport::plain).toList();
    }

    @Test
    void englishFailureReply() {
        useLanguage("en");
        var sender = mock(ConsoleCommandSender.class);
        CommandTestSupport.dispatch(new ThrowingExecutor(), sender, null, "replylang", new String[] {"boom"});
        assertThat(messagesTo(sender)).containsExactly("Command execution failed: the real reason");
    }

    @Test
    void chineseFailureReply() {
        useLanguage("zh");
        var sender = mock(ConsoleCommandSender.class);
        CommandTestSupport.dispatch(new ThrowingExecutor(), sender, null, "replylang", new String[] {"boom"});
        assertThat(messagesTo(sender)).containsExactly("命令执行出错: the real reason");
    }

    @Test
    void englishProcessingNotice() {
        useLanguage("en");
        var sender = mock(ConsoleCommandSender.class);
        CommandTestSupport.dispatch(new ThrowingExecutor(), sender, null, "replylang", new String[] {"slow"});
        assertThat(messagesTo(sender)).containsExactly("Processing...");
    }

    @Test
    void chineseProcessingNotice() {
        useLanguage("zh");
        var sender = mock(ConsoleCommandSender.class);
        CommandTestSupport.dispatch(new ThrowingExecutor(), sender, null, "replylang", new String[] {"slow"});
        assertThat(messagesTo(sender)).containsExactly("处理中...");
    }

    static Stream<Arguments> feedback() {
        return Stream.of(
                Arguments.of(Text.of(CommandKeys.COOLDOWN, Arg.number("seconds", 5L)),
                        "操作频繁，请 5 秒后再试", "Frequent operations, please try again in 5 seconds"),
                Arguments.of(Text.of(CommandKeys.USAGE, Arg.string("label", "cmdprobe"), Arg.string("format", "count <n>")),
                        "正确用法: /cmdprobe count <n>", "Correct Usage: /cmdprobe count <n>"),
                Arguments.of(Text.of(CommandKeys.UNKNOWN, Arg.string("label", "cmdprobe"), Arg.string("help", "help")),
                        "未知指令，请使用/cmdprobe help获取帮助", "Unknown command, please enter /cmdprobe help for help"),
                Arguments.of(Text.of(CommandKeys.TIMEOUT), "命令执行超时，已停止等待，命令仍在后台继续执行",
                        "Command execution timed out; no longer waiting. The command continues running in the background."));
    }

    @ParameterizedTest
    @MethodSource("feedback")
    void sameRejectionUsesEachRecipientsLanguage(Text text, String chinese, String english) {
        useLanguage("en");
        var zh = mock(Player.class);
        var en = mock(Player.class);
        var console = mock(ConsoleCommandSender.class);
        when(zh.locale()).thenReturn(Locale.SIMPLIFIED_CHINESE);
        when(en.locale()).thenReturn(Locale.US);
        services.messages().send(zh, text);
        services.messages().send(en, text);
        services.messages().send(console, text);
        assertThat(messagesTo(zh)).containsExactly(chinese);
        assertThat(messagesTo(en)).containsExactly(english);
        assertThat(messagesTo(console)).containsExactly(english);
    }

    @Test
    void argumentContentsAreLiteralAndKeepTheOriginalMessageColors() {
        useLanguage("en");
        for (String format : List.of("give <target>", "say <words...>")) {
            var component = services.messages().render(Text.of(CommandKeys.USAGE, Arg.string("label", "probe"),
                    Arg.string("format", format)), Locale.ENGLISH);
            assertThat(CommandMessageTestSupport.plain(component)).isEqualTo("Correct Usage: /probe " + format);
            assertThat(component.color()).isEqualTo(NamedTextColor.YELLOW);
        }
        var failure = services.messages().render(Text.of(CommandKeys.EXECUTION_FAILED,
                Arg.string("error", "literal <red> and <green>")), Locale.ENGLISH);
        assertThat(CommandMessageTestSupport.plain(failure)).isEqualTo("Command execution failed: literal <red> and <green>");
        assertThat(failure.color()).isEqualTo(NamedTextColor.RED);
        assertThat(failure).isEqualTo(Component.text("Command execution failed: literal <red> and <green>", NamedTextColor.RED));
    }

    @CmdExecutor(alias = "replylang")
    static class ThrowingExecutor extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Not exercised. */ }
        @CmdMapping(format = "boom")
        public void boom() { throw new IllegalStateException("the real reason"); }
        @AsyncCommand(showProcessing = true, timeout = 0)
        @CmdMapping(format = "slow")
        public void slow() { /* The capturing scheduler leaves this body pending. */ }
    }
}

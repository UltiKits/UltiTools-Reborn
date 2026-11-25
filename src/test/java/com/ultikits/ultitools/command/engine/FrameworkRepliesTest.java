package com.ultikits.ultitools.command.engine;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import net.kyori.adventure.text.Component;
import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;

@ExtendWith(CommandTestExtension.class)
class FrameworkRepliesTest {
    @Test void mistakesAtEveryDepthUseFrameworkRepliesAndValidPathsKeepPriority() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var executor = new Fixture();
            List<String> replies = new ArrayList<>();
            CommandSender sender = mock(CommandSender.class);
            doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(anyString());
            doAnswer(call -> replies.add(CommandMessageTestSupport.plain(call.getArgument(0))))
                    .when(sender).sendMessage(any(Component.class));
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            var source = CommandDispatchTest.source(sender);
            for (String line : List.of("test sett x", "test count abc", "test count", "test list extra", "test")) {
                assertThatCode(() -> dispatcher.execute(line, source)).doesNotThrowAnyException();
            }
            assertThat(replies).hasSize(7);
            assertThat(replies.getFirst()).contains("未知指令");
            assertThat(replies).filteredOn(text -> text.contains("正确用法")).hasSize(3);
            assertThat(dispatcher.execute("test count 5", source)).isEqualTo(1);
            assertThat(executor.count).isEqualTo(5);
            assertThat(replies).hasSize(7);
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("test ", source)).get().getList();
            assertThat(suggestions).extracting(s -> s.getText()).contains("count", "list", "help")
                    .doesNotContain("__ultitools_unmatched");
        }
    }
    @Test void caseNormalizationIncludesLiteralsAfterParametersAndRedispatchesOnlyOnce() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            Fixture executor = new Fixture();
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(dispatcher.execute("test AFTER MiXeD ADD", source)).isEqualTo(1);
            assertThat(executor.name).isEqualTo("MiXeD");
            assertThat(executor.calls).isEqualTo(1);
            assertThatCode(() -> dispatcher.execute("test COUNT not-a-number", source)).doesNotThrowAnyException();
            assertThat(executor.calls).isEqualTo(1);
        }
    }
    @Test void consoleTrailingAndRepeatedSpacesNormalizeTogetherWithLiteralCaseOnlyOnce() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            Fixture executor = new Fixture();
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(dispatcher.execute("test count 5 ", source)).isEqualTo(1);
            assertThat(executor.count).isEqualTo(5);
            assertThat(dispatcher.execute("test  AFTER  MiXeD  ADD  ", source)).isEqualTo(1);
            assertThat(executor.name).isEqualTo("MiXeD");
            assertThat(executor.calls).isEqualTo(2);
        }
    }

    @Test void oneRedispatchNormalizesAllLiteralsAndCannotLoopOnInvalidArguments() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            Fixture executor = new Fixture();
            var invocation = new CommandInvocation(executor);
            var compiled = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor,
                    "test", invocation::run, invocation::help);
            var commands = new RecordingCommands();
            var count = new AtomicInteger();
            var replies = new FrameworkReplies(executor, compiled.tree(), (input, source) -> {
                count.incrementAndGet();
                return commands.getDispatcher().execute(input, source);
            });
            commands.register(replies.tree());
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(commands.getDispatcher().execute("test AFTER MiXeD ADD", source)).isEqualTo(1);
            assertThat(count.get()).isEqualTo(1);
            assertThat(executor.calls).isEqualTo(1);
            count.set(0);
            assertThat(commands.getDispatcher().execute("test COUNT abc", source)).isEqualTo(1);
            assertThat(count.get()).isEqualTo(1);
            assertThat(executor.calls).isEqualTo(1);
        }
    }

    @Test void nativeLiteralWithOneTrailingSpaceIsRejectedBeforeARecoveryCallbackCanRun() {
        var dispatcher = CommandTestSupport.dispatcher(new Fixture(), "test");
        var source = CommandDispatchTest.source(mock(CommandSender.class));
        assertThatThrownBy(() -> dispatcher.execute("test list ", source)).isInstanceOf(CommandSyntaxException.class);
    }

    @Test void fallbackKeepsLaterParameterSuggestionsFromTheRealTree() {
        var dispatcher = CommandTestSupport.dispatcher(new Fixture(), "test");
        var source = CommandDispatchTest.source(mock(CommandSender.class));
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("test pair first ", source)).join();
        assertThat(suggestions.getList()).extracting(value -> value.getText()).containsExactly("second");
    }

    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        Integer count;
        String name;
        int calls;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "count <n>") public void count(@CmdParam("n") int n) { count = n;
            calls++; }
        @CmdMapping(format = "list") public void list() { calls++; }
        @CmdMapping(format = "pair <first> <second>") public void pair(@CmdParam("first") String first,
                @CmdParam(value = "second", suggest = "seconds") String second) { calls++; }
        public List<String> seconds() { return List.of("second"); }
        @CmdMapping(format = "after <name> add") public void after(@CmdParam("name") String value) { name = value;
            calls++; }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
}

package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.*;

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
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            var source = CommandDispatchTest.source(sender);
            for (String line : List.of("test sett x", "test count abc", "test count", "test list extra", "test")) {
                assertThatCode(() -> dispatcher.execute(line, source)).doesNotThrowAnyException();
            }
            assertThat(replies).hasSize(5);
            assertThat(replies.getFirst()).contains("未知指令");
            assertThat(replies.subList(1, 4)).allMatch(text -> text.contains("指令正确用法"));
            assertThat(dispatcher.execute("test count 5", source)).isEqualTo(1);
            assertThat(executor.count).isEqualTo(5);
            assertThat(replies).hasSize(5);
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
    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        Integer count;
        String name;
        int calls;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "count <n>") public void count(@CmdParam("n") int n) { count = n; calls++; }
        @CmdMapping(format = "list") public void list() { calls++; }
        @CmdMapping(format = "after <name> add") public void after(@CmdParam("name") String value) { name = value; calls++; }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
}

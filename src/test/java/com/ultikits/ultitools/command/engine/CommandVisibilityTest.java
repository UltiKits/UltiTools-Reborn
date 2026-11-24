package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import com.mojang.brigadier.CommandDispatcher;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import io.papermc.paper.command.brigadier.CommandSourceStack;

class CommandVisibilityTest {
    @Test void rootPermissionAndOpAreBothRequired() {
        var invocation = new CommandInvocation(new Root());
        var tree = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(new Root(), "root",
                invocation::run, invocation::help).tree();
        CommandSender sender = mock(CommandSender.class);
        var source = CommandDispatchTest.source(sender);
        assertThat(tree.canUse(source)).isFalse();
        when(sender.hasPermission("fixture.root")).thenReturn(true);
        assertThat(tree.canUse(source)).isFalse();
        when(sender.isOp()).thenReturn(true);
        assertThat(tree.canUse(source)).isTrue();
    }

    @Test void sharedPrefixesStayVisibleButPrivateMappingsDoNotLeakThroughUsageOrSuggestions() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var ulti = mockStatic(UltiTools.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var plugin = mock(UltiTools.class);
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0).equals("指令正确用法")
                    ? "usage /%s %s" : call.getArgument(0));
            Fixture executor = new Fixture();
            var dispatcher = dispatcher(executor);
            CommandSender sender = mock(CommandSender.class);
            List<String> replies = new ArrayList<>();
            doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(anyString());
            var source = CommandDispatchTest.source(sender);
            var shared = dispatcher.getRoot().getChild("test").getChild("shared");
            assertThat(shared.canUse(source)).isTrue();
            assertThat(shared.getChild("secret").canUse(source)).isFalse();
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("test shared ", source)).join();
            assertThat(suggestions.getList()).extracting(value -> value.getText()).contains("open").doesNotContain("secret");
            dispatcher.execute("test shared missing", source);
            assertThat(replies).isNotEmpty().allMatch(value -> !value.contains("secret"));
            dispatcher.execute("test SHARED SECRET", source);
            assertThat(executor.privateCalls).isZero();
            when(sender.hasPermission("fixture.secret")).thenReturn(true);
            dispatcher.execute("test SHARED SECRET", source);
            assertThat(executor.privateCalls).isEqualTo(1);
        }
    }

    @Test void aSharedArgumentDoesNotInvokeAnUnauthorizedProvider() {
        Fixture executor = new Fixture();
        var dispatcher = dispatcher(executor);
        var source = CommandDispatchTest.source(mock(CommandSender.class));
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("test choose ", source)).join();
        assertThat(suggestions.getList()).extracting(value -> value.getText()).contains("public").doesNotContain("private");
        assertThat(executor.privateSuggestions).isZero();
    }

    static CommandDispatcher<CommandSourceStack> dispatcher(BaseCommandExecutor executor) {
        var invocation = new CommandInvocation(executor);
        var command = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "fixture", invocation::run, invocation::help);
        return CommandTestSupport.dispatcher(executor, command.label());
    }

    @CmdExecutor(alias = "root", permission = "fixture.root", requireOp = true)
    static class Root extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) { /* Visibility fixture. */ }
    }

    @CmdExecutor(alias = "test")
    static class Fixture extends BaseCommandExecutor {
        int privateCalls;
        int privateSuggestions;
        @CmdMapping(format = "shared open") public void open() { /* Public sibling control. */ }
        @CmdMapping(format = "shared secret", permission = "fixture.secret") public void secret() { privateCalls++; }
        @CmdMapping(format = "choose <value> a", permission = "fixture.secret")
        public void choosePrivate(@CmdParam(value = "value", suggest = "privateValues") String value) { /* Mapping fixture. */ }
        @CmdMapping(format = "choose <value> b")
        public void choosePublic(@CmdParam(value = "value", suggest = "publicValues") String value) { /* Mapping fixture. */ }
        public List<String> privateValues() { privateSuggestions++; return List.of("private"); }
        public List<String> publicValues() { return List.of("public"); }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
}

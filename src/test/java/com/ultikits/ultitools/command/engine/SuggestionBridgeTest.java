package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;

class SuggestionBridgeTest {
    @AfterEach void cleanup() { TabCompletionManager.getInstance().unregister("@cmd1-test"); }
    @Test void namedProviderUsesTheAnnotationKeyAndFiltersThePrefix() throws Exception {
        TabCompletionManager.getInstance().register("@cmd1-test", context -> List.of("alpha", "beta"));
        assertThat(complete(new Fixture(), "test named a")).containsExactly("alpha");
    }
    @Test void methodProviderReceivesTheCurrentSenderAndInput() throws Exception {
        assertThat(complete(new Fixture(), "test method b")).containsExactly("beta");
    }
    @Test void executorHookIsUsedOnlyWhenTheParameterHasNoExplicitProvider() throws Exception {
        assertThat(complete(new Fixture(), "test fallback c")).containsExactly("custom");
    }
    @Test void unbindingDoesNotCallTheOldProvider() throws Exception {
        var executor = new Fixture();
        var command = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "test", (c,m,v) -> 1, c -> 1);
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> {});
        bindings.bind(executor, "test", executor, command);
        var commands = new RecordingCommands();
        bindings.onCommands(commands);
        bindings.unbind(executor);
        var dispatcher = commands.getDispatcher();
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("test method ", CommandDispatchTest.source(mock(CommandSender.class))))
                .get().getList()).isEmpty();
        assertThat(executor.providerCalls).isZero();
    }
    private static List<String> complete(Fixture executor, String input) throws Exception {
        var command = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "test", (c,m,v) -> 1, c -> 1);
        var commands = new RecordingCommands();
        commands.register(command.tree());
        var dispatcher = commands.getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, CommandDispatchTest.source(mock(CommandSender.class))))
                .get().getList().stream().map(s -> s.getText()).toList();
    }
    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        int providerCalls;
        @CmdMapping(format = "named <name>") public void named(@CmdParam(value = "name", suggest = "@cmd1-test") String name) { /* Fixture. */ }
        @CmdMapping(format = "method <name>") public void method(@CmdParam(value = "name", suggest = "names") String name) { /* Fixture. */ }
        @CmdMapping(format = "fallback <name>") public void fallback(@CmdParam("name") String name) { /* Fixture. */ }
        public List<String> names(CommandSender sender, String input) { providerCalls++; return List.of("alpha", "beta"); }
        @Override protected List<String> suggest(CommandSender sender, String label, String[] args) { return List.of("custom"); }
        @Override protected void handleHelp(CommandSender sender) { /* Fixture. */ }
    }
}

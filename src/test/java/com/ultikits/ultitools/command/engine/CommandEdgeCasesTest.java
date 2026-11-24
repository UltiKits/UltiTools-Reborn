package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;
import com.ultikits.ultitools.abstracts.command.parser.TypeParserRegistry;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;

class CommandEdgeCasesTest {
    @Test void bareRootGreedyMappingRunsWithAnEmptyArrayInsteadOfHelp() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var executor = new RootGreedy();
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            var source = CommandDispatchTest.source(mock(CommandSender.class));
            assertThat(dispatcher.execute("test", source)).isEqualTo(1);
            assertThat(executor.words).isEmpty();
            assertThat(dispatcher.execute("test a b", source)).isEqualTo(1);
            assertThat(executor.words).containsExactly("a", "b");
            assertThat(executor.help).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"parse bad", "many bad"})
    void customParserDetailsUseTheExistingRedReply(String arguments) throws Exception {
        var parser = new DetailParser();
        var registry = TypeParserRegistry.getInstance();
        registry.register(parser);
        try (var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            CommandSender sender = mock(CommandSender.class);
            List<String> replies = new ArrayList<>();
            doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(anyString());
            var dispatcher = CommandTestSupport.dispatcher(new Custom(), "test");
            assertThat(dispatcher.execute("test " + arguments, CommandDispatchTest.source(sender))).isEqualTo(1);
            assertThat(replies).containsExactly(org.bukkit.ChatColor.RED + "custom failure detail");
        } finally {
            registry.unregister(parser);
        }
    }

    @Test void normalizedLiteralsRunTheSameValidatorChainExactlyOnce() throws Exception {
        List<String> events = new ArrayList<>();
        var executor = new Guarded(ValidatorChain.builder().add(guard("first", true, events))
                .add(guard("second", false, events)).build());
        try (var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var dispatcher = CommandTestSupport.dispatcher(executor, "test");
            assertThat(dispatcher.execute("test COUNT 5", CommandDispatchTest.source(mock(CommandSender.class)))).isEqualTo(1);
            assertThat(events).containsExactly("first", "second", "refused first");
            assertThat(executor.calls).isZero();
        }
    }

    @Test void explicitlyRegisteredPrimaryLabelIsUsedInTheTypedContext() throws Exception {
        AtomicReference<String> actual = new AtomicReference<>();
        var executor = new Guarded(ValidatorChain.builder().add(new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) {
                actual.set(context.getCommandName());
                return ValidationResult.success();
            }
        }).build());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var dispatcher = CommandTestSupport.dispatcher(executor, "registered");
            assertThat(dispatcher.execute("registered count 2", CommandDispatchTest.source(mock(CommandSender.class)))).isEqualTo(1);
            assertThat(actual).hasValue("registered");
            assertThat(executor.calls).isEqualTo(1);
        }
    }

    @Test void consoleGetsUsageForExtraHelpArguments() throws Exception {
        try (var ulti = mockStatic(UltiTools.class)) {
            var plugin = mock(UltiTools.class);
            when(plugin.i18n("指令正确用法")).thenReturn("usage /%s %s");
            ulti.when(UltiTools::getInstance).thenReturn(plugin);
            var sender = mock(ConsoleCommandSender.class);
            List<String> replies = new ArrayList<>();
            doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(anyString());
            var executor = new Guarded(ValidatorChain.builder().build());
            assertThat(CommandTestSupport.dispatcher(executor, "test")
                    .execute("test help extra", CommandDispatchTest.source(sender))).isEqualTo(1);
            assertThat(replies).containsExactly("usage /test help");
        }
    }

    private static CommandValidator guard(String name, boolean allowed, List<String> events) {
        return new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) {
                events.add(name);
                return allowed ? ValidationResult.success() : ValidationResult.failure("denied");
            }
            @Override public void onRefused(CommandContext context) { events.add("refused " + name); }
        };
    }

    @CmdExecutor(alias = "test") static class RootGreedy extends BaseCommandExecutor {
        String[] words;
        int help;
        RootGreedy() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "<words...>") public void words(@CmdParam("words") String[] value) { words = value; }
        @Override protected void handleHelp(CommandSender sender) { help++; }
    }
    record Detail(String value) {}
    static class DetailParser implements TypeParser<Detail> {
        @Override public Class<Detail> getPrimaryType() { return Detail.class; }
        @Override public List<Class<?>> getSupportedTypes() { return List.of(Detail.class); }
        @Override public Detail parse(String value) { throw new TypeParseException("custom failure detail"); }
    }
    @CmdExecutor(alias = "test") static class Custom extends BaseCommandExecutor {
        Custom() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "parse <value>") public void parse(@CmdParam("value") Detail value) { /* Never reached. */ }
        @CmdMapping(format = "many <values...>") public void many(@CmdParam("values") Detail[] values) { /* Never reached. */ }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
    @CmdExecutor(alias = "annotated") static class Guarded extends BaseCommandExecutor {
        int calls;
        Guarded(ValidatorChain chain) { super(chain); }
        @CmdMapping(format = "count <n>") public void count(@CmdParam("n") int n) { calls++; }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }
}

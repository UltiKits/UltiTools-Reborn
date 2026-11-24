package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import java.util.ArrayList;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import io.papermc.paper.command.brigadier.CommandSourceStack;

class LiteralAlternativeCaseTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<UltiTools> ulti;
    private Fixture executor;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandSourceStack source;
    private final List<String> replies = new ArrayList<>();

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        ulti = mockStatic(UltiTools.class);
        var plugin = mock(UltiTools.class);
        when(plugin.i18n(anyString())).thenAnswer(call -> call.getArgument(0));
        ulti.when(UltiTools::getInstance).thenReturn(plugin);
        executor = new Fixture();
        dispatcher = CommandTestSupport.dispatcher(executor, "test");
        var sender = mock(CommandSender.class);
        doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(anyString());
        source = CommandDispatchTest.source(sender);
    }
    @AfterEach void tearDown() {
        ulti.close();
        bukkit.close();
    }

    @Test void uppercaseLiteralWinsOverAnOtherwiseValidStringArgument() throws Exception {
        assertThat(dispatcher.execute("test info ALL", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("all");
        assertThat(executor.bodies).isEqualTo(1);
    }
    @Test void uppercaseLiteralAfterAnArgumentWinsOverAnotherArgument() throws Exception {
        assertThat(dispatcher.execute("test after MiXeD ADD", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("literal:MiXeD");
        assertThat(executor.bodies).isEqualTo(1);
    }
    @Test void uppercaseFirstLiteralWinsOverARootGreedyArgument() throws Exception {
        assertThat(dispatcher.execute("test INFO all", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("all");
        assertThat(executor.bodies).isEqualTo(1);
    }
    @Test void automaticHelpWinsOverARootGreedyArgumentIgnoringCase() throws Exception {
        assertThat(dispatcher.execute("test HELP", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("help");
        assertThat(executor.bodies).isEqualTo(1);
    }
    @Test void anUnavailableLiteralDoesNotFallThroughToTheRootGreedyBody() throws Exception {
        assertThat(dispatcher.execute("test SECRET", source)).isEqualTo(1);
        assertThat(executor.route).isNull();
        assertThat(executor.bodies).isZero();
        assertThat(replies).containsExactly("指令正确用法");
    }
    @Test void anUnavailableExactLiteralStillReceivesTheFrameworkReply() throws Exception {
        assertThat(dispatcher.execute("test secret", source)).isEqualTo(1);
        assertThat(executor.route).isNull();
        assertThat(executor.bodies).isZero();
        assertThat(replies).containsExactly("指令正确用法");
    }
    @Test void unrelatedArgumentValuesAndOrdinaryLowercaseLiteralsStillExecute() throws Exception {
        assertThat(dispatcher.execute("test info all", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("all");
        assertThat(dispatcher.execute("test info Data", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("argument:Data");
        assertThat(dispatcher.execute("test free Words", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("words:free Words");
        assertThat(executor.bodies).isEqualTo(3);
    }
    @Test void handWrittenTreesKeepTheirOwnCaseSensitiveRouting() throws Exception {
        var raw = LiteralArgumentBuilder.<CommandSourceStack>literal("raw")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("all").executes(context -> 11))
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("value", StringArgumentType.word())
                        .executes(context -> 12)).build();
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> { /* No client in this unit fixture. */ });
        var owner = new Object();
        bindings.bind(owner, "raw", owner, new CompiledCommand("raw", List.of(), null, raw, "raw"));
        var commands = new RecordingCommands();
        bindings.onCommands(commands);
        assertThat(commands.getDispatcher().execute("raw ALL", source)).isEqualTo(12);
        assertThat(commands.getDispatcher().execute("raw all", source)).isEqualTo(11);
    }

    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        String route;
        int bodies;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "info all") public void all() { route = "all"; bodies++; }
        @CmdMapping(format = "secret", permission = "probe.secret") public void secret() { route = "secret"; bodies++; }
        @CmdMapping(format = "info <text>") public void argument(@CmdParam("text") String text) {
            route = "argument:" + text; bodies++;
        }
        @CmdMapping(format = "after <name> add") public void literalAfter(@CmdParam("name") String name) {
            route = "literal:" + name; bodies++;
        }
        @CmdMapping(format = "after <name> <action>") public void argumentAfter(@CmdParam("name") String name,
                @CmdParam("action") String action) { route = "argument:" + action; bodies++; }
        @CmdMapping(format = "<words...>") public void words(@CmdParam("words") String[] words) {
            route = "words:" + String.join(" ", words); bodies++;
        }
        @Override protected void handleHelp(CommandSender sender) { route = "help"; bodies++; }
    }
}

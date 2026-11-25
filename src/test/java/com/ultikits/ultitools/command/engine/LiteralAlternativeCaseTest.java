package com.ultikits.ultitools.command.engine;

import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import java.util.ArrayList;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.plugin.PluginManager;
import org.mockito.ArgumentCaptor;
import net.kyori.adventure.text.Component;
import com.ultikits.ultitools.manager.CommandManager;
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

@ExtendWith(CommandTestExtension.class)
class LiteralAlternativeCaseTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<UltiTools> ulti;
    private Fixture executor;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandSourceStack source;
    private final List<Component> replies = new ArrayList<>();

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
        doAnswer(call -> replies.add(Component.text(call.getArgument(0, String.class)))).when(sender).sendMessage(anyString());
        doAnswer(call -> replies.add(call.getArgument(0))).when(sender).sendMessage(any(Component.class));
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
        assertThat(executor.secretBodies).isZero();
        assertThat(executor.greedyBodies).isZero();
        List<Component> hiddenReplies = List.copyOf(replies);
        assertThat(hiddenReplies).isNotEmpty();
        replies.clear();

        assertThat(hiddenReplies).containsExactlyElementsOf(unknownRepliesWithoutRootGreedy());
        assertThat(executor.secretBodies).isZero();
        assertThat(executor.greedyBodies).isZero();

        when(source.getSender().hasPermission("probe.secret")).thenReturn(true);
        assertThat(dispatcher.execute("test SECRET", source)).isEqualTo(1);
        assertThat(executor.secretBodies).isEqualTo(1);
        assertThat(executor.greedyBodies).isZero();
        assertThat(replies).isEmpty();

        when(source.getSender().hasPermission("probe.secret")).thenReturn(false);
        assertThat(dispatcher.execute("test MISSING", source)).isEqualTo(1);
        assertThat(executor.route).isEqualTo("words:MISSING");
        assertThat(executor.secretBodies).isEqualTo(1);
        assertThat(executor.greedyBodies).isEqualTo(1);
        assertThat(replies).isEmpty();
    }
    @Test void anUnavailableExactLiteralWithRootGreedyUsesTheRegisteredListener() throws Exception {
        List<Component> expected = unknownRepliesWithoutRootGreedy();
        assertThat(expected).isNotEmpty();
        var plugin = UltiTools.getInstance();
        var plugins = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        var lifecycle = CommandTestSupport.lifecycle(plugin);
        var engine = new CommandEngine(new StringNativeArgumentTypes(), Logger.getAnonymousLogger());
        assertThat(engine.register(executor, "fixture", executor, "fixture", "", false, List.of("test"))).isTrue();
        new CommandManager(engine).registerInternalListeners();
        var registeredListener = ArgumentCaptor.forClass(HiddenCommandListener.class);
        verify(plugins).registerEvents(registeredListener.capture(), eq(plugin));
        var registeredDispatcher = lifecycle.fire().getDispatcher();

        assertThatThrownBy(() -> registeredDispatcher.execute("test secret", source))
                .isInstanceOf(CommandSyntaxException.class);
        assertThat(replies).isEmpty();
        var event = new UnknownCommandEvent(source, "test secret", Component.text("vanilla syntax error"));
        registeredListener.getValue().onUnknownCommand(event);

        assertThat(event.message()).isNull();
        assertThat(replies).containsExactlyElementsOf(expected);
        assertThat(executor.secretBodies).isZero();
        assertThat(executor.greedyBodies).isZero();
    }
    @Test void anUnavailableExactLiteralIsRejectedByBrigadierBeforeAnyCallback() {
        assertThatThrownBy(() -> dispatcher.execute("test secret", source)).isInstanceOf(CommandSyntaxException.class);
        assertThat(executor.route).isNull();
        assertThat(executor.bodies).isZero();
        assertThat(replies).isEmpty();
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
    @Test void tradeStyleLiteralWinsOverASingleStringParameterForEveryCase() throws Exception {
        for (String spelling : List.of("accept", "ACCEPT", "Accept")) {
            Trade trade = new Trade();
            var commands = CommandTestSupport.dispatcher(trade, "trade");
            assertThat(commands.execute("trade " + spelling, source)).isEqualTo(1);
            assertThat(trade.route).isEqualTo("accepted");
            assertThat(trade.calls).isEqualTo(1);
        }
        Trade quoted = new Trade();
        assertThat(CommandTestSupport.dispatcher(quoted, "trade").execute("trade \"Accept\"", source)).isEqualTo(1);
        assertThat(quoted.route).isEqualTo("player:Accept");
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

    private List<Component> unknownRepliesWithoutRootGreedy() throws Exception {
        var invocation = new CommandInvocation(executor, "control");
        var compiled = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor, "fixture",
                List.of("control"), "", false, invocation::run, invocation::help);
        assertThat(compiled.tree().getChild("words")).isNotNull();
        var builder = compiled.tree().createBuilder();
        compiled.tree().getChildren().stream().filter(child -> !"words".equals(child.getName())).forEach(builder::then);
        var controlTree = builder.build();
        assertThat(controlTree.getChildren()).hasSize(compiled.tree().getChildren().size() - 1);
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> { /* No client synchronization. */ });
        var frameworkReplies = new FrameworkReplies(executor, controlTree,
                (input, stack) -> bindings.dispatch("control", input, stack));
        bindings.bind(executor, "fixture", executor,
                new CompiledCommand("control", List.of(), "fixture", frameworkReplies.tree(), compiled.signature()),
                frameworkReplies);
        var commands = new RecordingCommands();
        bindings.onCommands(commands);
        assertThat(commands.getDispatcher().execute("control MISSING", source)).isEqualTo(1);
        List<Component> expected = replies.stream().map(message -> message.replaceText(replacement -> replacement.matchLiteral("/control ").replacement("/test ")).compact()).toList();
        replies.clear();
        return expected;
    }

    @CmdExecutor(alias = "trade") static class Trade extends BaseCommandExecutor {
        String route;
        int calls;
        Trade() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "<player>") public void player(@CmdParam("player") String player) {
            route = "player:" + player;
            calls++;
        }
        @CmdMapping(format = "accept") public void accept() {
            route = "accepted";
            calls++;
        }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help"); }
    }

    @CmdExecutor(alias = "test") static class Fixture extends BaseCommandExecutor {
        String route;
        int bodies;
        int secretBodies;
        int greedyBodies;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "info all") public void all() { route = "all";
            bodies++; }
        @CmdMapping(format = "secret", permission = "probe.secret") public void secret() { route = "secret";
            secretBodies++;
            bodies++; }
        @CmdMapping(format = "info <text>") public void argument(@CmdParam("text") String text) {
            route = "argument:" + text;
            bodies++;
        }
        @CmdMapping(format = "after <name> add") public void literalAfter(@CmdParam("name") String name) {
            route = "literal:" + name;
            bodies++;
        }
        @CmdMapping(format = "after <name> <action>") public void argumentAfter(@CmdParam("name") String name,
                @CmdParam("action") String action) { route = "argument:" + action;
            bodies++; }
        @CmdMapping(format = "<words...>") public void words(@CmdParam("words") String[] words) {
            route = "words:" + String.join(" ", words);
            greedyBodies++;
            bodies++;
        }
        @Override protected void handleHelp(CommandSender sender) { route = "help";
            bodies++; }
    }
}

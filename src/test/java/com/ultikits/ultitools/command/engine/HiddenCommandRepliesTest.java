package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class HiddenCommandRepliesTest {
    private static final Component VANILLA_ERROR = Component.text("Incorrect argument for command", NamedTextColor.RED);
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<UltiTools> ulti;
    private Fixture executor;
    private Player player;
    private CommandSourceStack source;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandDispatcher<CommandSourceStack> eventDispatcher;
    private HiddenCommandListener listener;
    private CommandEngine engine;
    private PluginManager plugins;
    private UltiTools plugin;
    private final List<String> replies = new ArrayList<>();
    private final AtomicInteger redispatches = new AtomicInteger();

    @BeforeEach void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        ulti = mockStatic(UltiTools.class);
        plugin = mock(UltiTools.class);
        when(plugin.i18n(anyString())).thenAnswer(call -> "指令正确用法".equals(call.getArgument(0))
                ? "usage /%s %s" : call.getArgument(0));
        ulti.when(UltiTools::getInstance).thenReturn(plugin);
        executor = new Fixture();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        doAnswer(call -> replies.add(call.getArgument(0))).when(player).sendMessage(anyString());
        source = CommandTestSupport.source(player);
        var invocation = new CommandInvocation(executor, "hidden");
        var compiled = new CommandTreeCompiler(new StringNativeArgumentTypes()).compile(executor,
                "fixture", invocation::run, invocation::help);
        var bindings = new CommandBindings(Logger.getAnonymousLogger(), () -> { /* No client synchronization. */ });
        var frameworkReplies = new FrameworkReplies(executor, compiled.tree(), (input, stack) -> {
            redispatches.incrementAndGet();
            return bindings.dispatch("hidden", input, stack);
        });
        bindings.bind(executor, "fixture", executor,
                new CompiledCommand("hidden", List.of(), "fixture", frameworkReplies.tree(), compiled.signature()), frameworkReplies);
        var commands = new RecordingCommands();
        bindings.onCommands(commands);
        dispatcher = commands.getDispatcher();
        installEventEngine();
    }

    private void installEventEngine() {
        plugins = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        doAnswer(call -> {
            listener = call.getArgument(0);
            return null;
        }).when(plugins).registerEvents(any(HiddenCommandListener.class), eq(plugin));
        var lifecycle = CommandTestSupport.lifecycle(plugin);
        engine = new CommandEngine(new StringNativeArgumentTypes(), Logger.getAnonymousLogger());
        engine.register(executor, "fixture", executor, "fixture", "", false, List.of("hidden", "alternate"));
        engine.register(executor, "fixture", new Locked(), "locked", "fixture.root", false, List.of("locked"));
        eventDispatcher = lifecycle.fire("ultitools").getDispatcher();
        eventDispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("foreign")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("secret").requires(stack -> false)));
    }

    @AfterEach void tearDown() {
        ulti.close();
        bukkit.close();
    }

    @ParameterizedTest
    @CsvSource({
        "secret, missing, 0, true",
        "SECRET, MISSING, 0, false",
        "secret extra arguments, missing extra arguments, 0, true",
        "SECRET extra arguments, MISSING extra arguments, 0, false",
        "shared secret, shared missing, 0, true",
        "shared SECRET, shared MISSING, 0, false",
        "SHARED SECRET, SHARED MISSING, 1, false",
        "shared secret extra arguments, shared missing extra arguments, 0, true",
        "number not-a-number, missing not-a-number, 0, true",
        "NUMBER not-a-number, MISSING not-a-number, 0, false",
        "console, missing, 0, true",
        "CONSOLE, MISSING, 0, false",
        "console extra arguments, missing extra arguments, 0, true",
        "operator, missing, 0, true",
        "SHARED secret, SHARED missing, 1, false"
    })
    void hiddenAndMissingCommandsSendIdenticalOrderedReplies(String hidden, String missing, int retries,
            boolean nativeRefusal) throws Exception {
        assertThat(dispatcher.execute("hidden " + missing, source)).isEqualTo(1);
        List<String> expected = List.copyOf(replies);
        assertThat(expected).isNotEmpty().noneMatch(message -> message.contains("secret")
                || message.contains("console") || message.contains("number") || message.contains("operator"));
        assertThat(redispatches.get()).isEqualTo(retries);
        replies.clear();
        redispatches.set(0);

        if (nativeRefusal) {
            assertThatThrownBy(() -> dispatcher.execute("hidden " + hidden, source)).isInstanceOf(CommandSyntaxException.class);
            assertThat(replies).isEmpty();
            UnknownCommandEvent event = event("hidden " + hidden);
            listener.onUnknownCommand(event);
            assertThat(event.message()).isNull();
        } else {
            assertThat(dispatcher.execute("hidden " + hidden, source)).isEqualTo(1);
        }

        assertThat(replies).containsExactlyElementsOf(expected);
        assertThat(redispatches.get()).isEqualTo(retries);
        assertThat(executor.hiddenCalls).isZero();
        assertThat(executor.visibleCalls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "alternate", "ultitools:hidden", "ultitools:alternate"})
    void exactHiddenLiteralUsesTheRegisteredListenerForEveryDeliveredLabel(String label) throws Exception {
        assertThat(eventDispatcher.execute(label + " missing", source)).isEqualTo(1);
        List<String> expected = List.copyOf(replies);
        assertThat(expected).isNotEmpty();
        replies.clear();
        assertThatThrownBy(() -> eventDispatcher.execute(label + " secret", source)).isInstanceOf(CommandSyntaxException.class);
        assertThat(replies).isEmpty();
        UnknownCommandEvent event = event(label + " secret");

        assertThat(listener).isNotNull();
        listener.onUnknownCommand(event);

        assertThat(event.message()).isNull();
        assertThat(replies).containsExactlyElementsOf(expected);
        assertThat(executor.hiddenCalls).isZero();
        verify(plugins).registerEvents(any(HiddenCommandListener.class), eq(plugin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden number not-a-number", "hidden secret extra", "hidden typed not-a-number secret"})
    void authorizedSyntaxAndArgumentFailuresKeepTheirOriginalEventMessage(String input) throws Exception {
        when(player.hasPermission("fixture.secret")).thenReturn(true);
        assertThat(eventDispatcher.execute(input, source)).isEqualTo(1);
        replies.clear();
        UnknownCommandEvent event = event(input);

        listener.onUnknownCommand(event);

        assertThat(event.message()).isEqualTo(VANILLA_ERROR);
        assertThat(replies).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign secret", "foreign:hidden secret", "locked secret", "ultitools:locked secret"})
    void foreignOrUnavailableRootsKeepTheirOriginalEventMessage(String input) throws Exception {
        assertThatThrownBy(() -> eventDispatcher.execute(input, source)).isInstanceOf(CommandSyntaxException.class);
        UnknownCommandEvent event = event(input);

        listener.onUnknownCommand(event);

        assertThat(event.message()).isEqualTo(VANILLA_ERROR);
        assertThat(replies).isEmpty();
    }

    @Test void invalidArgumentBeforeAHiddenLiteralIsNotTakenOver() throws Exception {
        String input = "hidden typed not-a-number secret";
        assertThat(eventDispatcher.execute(input, source)).isEqualTo(1);
        replies.clear();
        UnknownCommandEvent event = event(input);

        listener.onUnknownCommand(event);

        assertThat(event.message()).isEqualTo(VANILLA_ERROR);
        assertThat(replies).isEmpty();
    }

    @Test void anUnloadedBindingDoesNotClaimItsFormerRegisteredLabels() throws Exception {
        engine.unregister(executor);
        assertThatThrownBy(() -> eventDispatcher.execute("ultitools:hidden secret", source))
                .isInstanceOf(CommandSyntaxException.class);
        UnknownCommandEvent event = event("ultitools:hidden secret");

        listener.onUnknownCommand(event);

        assertThat(event.message()).isEqualTo(VANILLA_ERROR);
        assertThat(replies).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"secret, 0", "SECRET, 1", "shared secret, 0", "SHARED SECRET, 1"})
    void authorizedSenderStillExecutesTheHiddenMappingOnce(String input, int retries) throws Exception {
        when(player.hasPermission("fixture.secret")).thenReturn(true);

        assertThat(dispatcher.execute("hidden " + input, source)).isEqualTo(1);

        assertThat(executor.hiddenCalls).isEqualTo(1);
        assertThat(executor.visibleCalls).isZero();
        assertThat(redispatches.get()).isEqualTo(retries);
        assertThat(replies).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared open", "SHARED OPEN"})
    void visibleCommandKeepsCaseRecoveryAndExecutesOnce(String input) throws Exception {
        assertThat(dispatcher.execute("hidden " + input, source)).isEqualTo(1);

        assertThat(executor.visibleCalls).isEqualTo(1);
        assertThat(executor.hiddenCalls).isZero();
        assertThat(redispatches.get()).isEqualTo(input.equals("shared open") ? 0 : 1);
        assertThat(replies).isEmpty();
    }

    private UnknownCommandEvent event(String input) {
        return new UnknownCommandEvent(source, input, VANILLA_ERROR);
    }

    @CmdExecutor(alias = "hidden")
    static class Fixture extends BaseCommandExecutor {
        int hiddenCalls;
        int visibleCalls;
        Fixture() { super(ValidatorChain.builder().build()); }
        @CmdMapping(format = "secret", permission = "fixture.secret")
        public void secret() { hiddenCalls++; }
        @CmdMapping(format = "shared secret", permission = "fixture.secret")
        public void sharedSecret() { hiddenCalls++; }
        @CmdMapping(format = "number <value>", permission = "fixture.secret")
        public void number(@CmdParam("value") int value) { hiddenCalls++; }
        @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
        @CmdMapping(format = "console")
        public void console() { hiddenCalls++; }
        @CmdMapping(format = "operator", requireOp = true)
        public void operator() { hiddenCalls++; }
        @CmdMapping(format = "shared")
        public void shared() { visibleCalls++; }
        @CmdMapping(format = "shared open")
        public void open() { visibleCalls++; }
        @CmdMapping(format = "typed <value> secret", permission = "fixture.secret")
        public void typedSecret(@CmdParam("value") int value) { hiddenCalls++; }
        @CmdMapping(format = "typed <value> open")
        public void typedOpen(@CmdParam("value") int value) { visibleCalls++; }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("help: shared open"); }
    }

    @CmdExecutor(alias = "locked", permission = "fixture.root")
    static class Locked extends BaseCommandExecutor {
        @CmdMapping(format = "secret", permission = "fixture.secret")
        public void secret() { /* Rejected by the root requirement. */ }
        @Override protected void handleHelp(CommandSender sender) { sender.sendMessage("locked help"); }
    }
}

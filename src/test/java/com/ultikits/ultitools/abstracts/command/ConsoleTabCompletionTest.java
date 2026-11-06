package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.commands.tabcomplete.MethodInvocationCompleter;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/** Regression coverage for sender-aware completion through the Bukkit entry point (#547). */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConsoleTabCompletionTest {
    private ServerMock server;
    private Command command;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        command = mock(Command.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private List<String> complete(BaseCommandExecutor executor, CommandSender sender, String... args) {
        return executor.onTabComplete(sender, command, "fixture", args);
    }

    @Test
    void consoleCompletesLiteralsAndFirstAndLaterParameters() {
        SenderExecutor executor = new SenderExecutor();
        CommandSender console = server.getConsoleSender();
        assertThat(complete(executor, console, "sen")).contains("sender", "senderstring", "senderargs");
        assertThat(complete(executor, console, "sp")).containsExactly("spawn");
        assertThat(complete(executor, console, "senderstring", "sp")).containsExactly("spawn");
        assertThat(executor.receivedSender).isSameAs(console);
        assertThat(executor.receivedInput).isEqualTo("sp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sender", "senderstring", "senderargs"})
    void senderProvidersReceivePlayerAndNonPlayer(String mapping) {
        SenderExecutor executor = new SenderExecutor();
        for (CommandSender sender : Arrays.asList(server.getConsoleSender(), server.addPlayer("player"),
                mock(RemoteConsoleCommandSender.class), mock(CommandSender.class))) {
            assertThat(complete(executor, sender, mapping, "sp")).containsExactly("spawn");
            assertThat(executor.receivedSender).isSameAs(sender);
        }
        if ("senderargs".equals(mapping)) {
            assertThat(executor.receivedCommand).isSameAs(command);
            assertThat(executor.receivedArgs).containsExactly(mapping, "sp");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"player", "playerstring", "playerargs"})
    void playerProvidersAreSkippedForConsoleAndPreservedForPlayers(String mapping) {
        SenderExecutor executor = new SenderExecutor();
        assertThat(complete(executor, server.getConsoleSender(), mapping, "")).isEmpty();
        assertThat(executor.playerCalls).isZero();
        assertThat(complete(executor, server.addPlayer("player"), mapping, "")).containsExactly("playerResult");
        assertThat(executor.playerCalls).isEqualTo(1);
    }

    @Test
    void senderIndependentProvidersAndBuiltInsWorkForConsole() {
        SenderExecutor executor = new SenderExecutor();
        CommandSender sender = server.getConsoleSender();
        assertThat(complete(executor, sender, "zero", "sp")).containsExactly("spawn");
        assertThat(complete(executor, sender, "string", "sp")).containsExactly("spawn");
        assertThat(complete(executor, sender, "boolean", "t")).containsExactly("true");
        server.addPlayer("Steve");
        assertThat(complete(executor, sender, "online", "St")).containsExactly("Steve");
    }

    @Test
    void mappingTargetsFilterLiteralsAndParametersForBothSenderKinds() {
        SenderExecutor executor = new SenderExecutor();
        CommandSender console = server.getConsoleSender();
        Player player = server.addPlayer("player");
        assertThat(complete(executor, console, "")).contains("consoleonly").doesNotContain("playeronly");
        assertThat(complete(executor, player, "")).contains("playeronly").doesNotContain("consoleonly");
        assertThat(complete(executor, console, "playeronly", "")).isEmpty();
        assertThat(complete(executor, player, "consoleonly", "")).isEmpty();
    }

    @Test
    void classTargetsAreRespected() {
        CommandSender console = server.getConsoleSender();
        Player player = server.addPlayer("player");
        assertThat(complete(new PlayerExecutor(), console, "")).isEmpty();
        assertThat(complete(new ConsoleExecutor(), player, "")).isEmpty();
        assertThat(complete(new ConsoleExecutor(), console, "senderstring", "sp")).containsExactly("spawn");
        assertThat(complete(new PlayerExecutor(), player, "senderstring", "sp")).containsExactly("spawn");
    }

    @Test
    void deniedPermissionAndOpFilterEveryPositionSilentlyBeforeProviderInvocation() {
        SenderExecutor executor = new SenderExecutor();
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        assertThat(complete(executor, sender, "")).doesNotContain("secret", "oponly");
        executor.receivedSender = null;
        assertThat(complete(executor, sender, "secret", "")).isEmpty();
        assertThat(complete(executor, sender, "oponly", "")).isEmpty();
        assertThat(executor.receivedSender).isNull();
        when(sender.hasPermission("fixture.secret")).thenReturn(true);
        when(sender.isOp()).thenReturn(true);
        assertThat(complete(executor, sender, "secret", "sp")).containsExactly("spawn");
        assertThat(complete(executor, sender, "oponly", "sp")).containsExactly("spawn");
    }

    @Test
    void classPermissionAndOpAreRespectedWithoutRunningValidators() {
        ClassGatedExecutor executor = new ClassGatedExecutor();
        CommandSender sender = mock(CommandSender.class);
        assertThat(complete(executor, sender, "")).isEmpty();
        when(sender.hasPermission("fixture.class")).thenReturn(true);
        assertThat(complete(executor, sender, "")).isEmpty();
        when(sender.isOp()).thenReturn(true);
        assertThat(complete(executor, sender, "senderstring", "sp")).containsExactly("spawn");
    }

    @Test
    void customCompleterReceivesNonPlayerContext() {
        TabCompletionManager manager = TabCompletionManager.getInstance();
        manager.register("@consoleFixture", context -> {
            assertThat(context.getPlayer()).isNull();
            return Arrays.asList("customResult");
        });
        try {
            assertThat(complete(new SenderExecutor(), server.getConsoleSender(), "custom", ""))
                    .containsExactly("customResult");
        } finally {
            manager.unregister("@consoleFixture");
        }
    }

    @Test
    void legacyProtectedPlayerOverrideIsStillUsed() {
        LegacyOverrideExecutor executor = new LegacyOverrideExecutor();
        assertThat(complete(executor, server.addPlayer("player"), "")).containsExactly("overrideResult");
        assertThat(complete(executor, server.getConsoleSender(), "senderstring", "sp")).containsExactly("spawn");
    }

    @Test
    void classifierAcceptsExactSenderShapesAndRejectsArbitrarySenderSubtypes() {
        assertThat(MethodInvocationCompleter.isInvocableSuggestSignature(new Class<?>[]{CommandSender.class})).isTrue();
        assertThat(MethodInvocationCompleter.isInvocableSuggestSignature(
                new Class<?>[]{CommandSender.class, String.class})).isTrue();
        assertThat(MethodInvocationCompleter.isInvocableSuggestSignature(
                new Class<?>[]{CommandSender.class, Command.class, String[].class})).isTrue();
        assertThat(MethodInvocationCompleter.isInvocableSuggestSignature(
                new Class<?>[]{ConsoleCommandSender.class, String.class})).isFalse();
    }

    @Test
    void deniedTargetDoesNotTouchSenderOrProvider() {
        CommandSender sender = mock(CommandSender.class);
        assertThat(complete(new PlayerExecutor(), sender, "senderstring", "")).isEmpty();
        verifyNoInteractions(sender);
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    static class SenderExecutor extends BaseCommandExecutor {
        CommandSender receivedSender;
        String receivedInput;
        Command receivedCommand;
        String[] receivedArgs;
        int playerCalls;

        @Override protected void handleHelp(CommandSender sender) {
            // Help is not exercised by completion requests.
        }
        @CmdMapping(format = "<name>")
        public void first(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "sender <name>")
        public void sender(@CmdParam(value = "name", suggest = "suggestSender") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "senderstring <name>")
        public void senderString(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "senderargs <name>")
        public void senderArgs(@CmdParam(value = "name", suggest = "suggestSenderArgs") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "player <name>")
        public void player(@CmdParam(value = "name", suggest = "suggestPlayer") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "playerstring <name>")
        public void playerString(@CmdParam(value = "name", suggest = "suggestPlayerString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "playerargs <name>")
        public void playerArgs(@CmdParam(value = "name", suggest = "suggestPlayerArgs") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "zero <name>")
        public void zero(@CmdParam(value = "name", suggest = "suggestZero") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "string <name>")
        public void string(@CmdParam(value = "name", suggest = "suggestString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "boolean <name>")
        public void bool(@CmdParam(value = "name", suggest = "@boolean") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "online <name>")
        public void online(@CmdParam(value = "name", suggest = "@players") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "custom <name>")
        public void custom(@CmdParam(value = "name", suggest = "@consoleFixture") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
        @CmdMapping(format = "playeronly <name>")
        public void playerOnly(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
        @CmdMapping(format = "consoleonly <name>")
        public void consoleOnly(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "secret <name>", permission = "fixture.secret")
        public void secret(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        @CmdMapping(format = "oponly <name>", requireOp = true)
        public void opOnly(@CmdParam(value = "name", suggest = "suggestSenderString") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }

        public List<String> suggestSender(CommandSender sender) {
            receivedSender = sender;
            return Arrays.asList("spawn", "other");
        }
        public List<String> suggestSenderString(CommandSender sender, String input) {
            receivedInput = input;
            return suggestSender(sender);
        }
        public List<String> suggestSenderArgs(CommandSender sender, Command currentCommand, String[] args) {
            receivedCommand = currentCommand;
            receivedArgs = args;
            return suggestSender(sender);
        }
        public List<String> suggestPlayer(Player player) {
            assertThat(player).isNotNull();
            playerCalls++;
            return Arrays.asList("playerResult");
        }
        public List<String> suggestPlayerString(Player player, String input) { return suggestPlayer(player); }
        public List<String> suggestPlayerArgs(Player player, Command currentCommand, String[] args) {
            return suggestPlayer(player);
        }
        public List<String> suggestZero() { return Arrays.asList("spawn", "other"); }
        public List<String> suggestString(String input) { return Arrays.asList(input + "awn"); }
    }

    static class TargetExecutor extends BaseCommandExecutor {
        @Override protected void handleHelp(CommandSender sender) {
            // Help is not exercised by completion requests.
        }
        @CmdMapping(format = "senderstring <name>")
        public void mapping(@CmdParam(value = "name", suggest = "suggestNames") String name) {
            // Mapping fixture only; completion never executes command bodies.
        }
        public List<String> suggestNames(CommandSender sender, String input) { return Arrays.asList("spawn"); }
    }
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    static class PlayerExecutor extends TargetExecutor { }
    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    static class ConsoleExecutor extends TargetExecutor { }
    @CmdExecutor(alias = "gated", permission = "fixture.class", requireOp = true)
    static class ClassGatedExecutor extends SenderExecutor { }
    static class LegacyOverrideExecutor extends SenderExecutor {
        @Override protected List<String> suggest(Player player, Command currentCommand, String[] args) {
            return Arrays.asList("overrideResult");
        }
    }
}

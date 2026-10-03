package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.ReloadReport;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.exceptions.CommandException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.TestHelper;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UltiToolsCommandsTest {

    private ServerMock server;
    private PlayerMock player;
    private Command mockCommand;
    private UltiToolsCommands executor;
    private PluginManager mockPluginManager;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        
        player = server.addPlayer("testplayer");
        player.setOp(true);
        
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("ul");
        
        mockPluginManager = mock(PluginManager.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getPluginManager()).thenReturn(mockPluginManager);
        });
        
        executor = new UltiToolsCommands();
    }

    @AfterEach
    void tearDown() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("Should show help message")
    void testHelp() {
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"help"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("UltiTools");
        assertThat(message).contains("reload");
    }

    @Test
    @DisplayName("Should list all plugins")
    void testListPlugins() {
        UltiToolsPlugin plugin1 = mock(UltiToolsPlugin.class);
        when(plugin1.getPluginName()).thenReturn("TestPlugin1");
        when(plugin1.getVersion()).thenReturn("1.0.0");
        
        UltiToolsPlugin plugin2 = mock(UltiToolsPlugin.class);
        when(plugin2.getPluginName()).thenReturn("TestPlugin2");
        when(plugin2.getVersion()).thenReturn("2.0.0");
        
        List<UltiToolsPlugin> plugins = Arrays.asList(plugin1, plugin2);
        when(mockPluginManager.getPluginList()).thenReturn(plugins);
        
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"list"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message1 = player.nextMessage();
        String message2 = player.nextMessage();
        
        assertThat(message1).contains("TestPlugin1").contains("1.0.0");
        assertThat(message2).contains("TestPlugin2").contains("2.0.0");
    }

    @Test
    @DisplayName("Should list empty plugin list")
    void testListEmptyPlugins() {
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"list"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        // No exception should be thrown
    }

    @Test
    @DisplayName("Should reload plugins")
    void testReloadPlugins() throws IOException {
        // Mock the reload method to not throw exception
        UltiTools mockInstance = UltiTools.getInstance();
        
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"reload"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        // Should complete without exception
    }

    @Test
    @DisplayName("GATE-05 group two (08-21): should wrap a reloadPlugins() IOException as CommandException")
    void testReloadPluginsWrapsIOExceptionAsCommandException() throws IOException {
        // Given - reload plugins() failing is exercised directly, not through the full
        // onCommand dispatch (which defers to a scheduled task and swallows the exception into
        // an async runnable), so the assertion below reaches reloadPlugins() synchronously.
        IOException cause = new IOException("disk error");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getPluginManager()).thenReturn(mockPluginManager);
            try {
                doThrow(cause).when(ultiTools).reloadPlugins();
            } catch (IOException ignored) {
                // reloadPlugins() declares IOException; doThrow(...).when(...) never actually
                // invokes the real method, so this branch is unreachable at runtime and exists
                // only to satisfy the checked-exception signature on the stubbed call.
            }
        });
        UltiToolsCommands freshExecutor = new UltiToolsCommands();

        // When / Then
        assertThatThrownBy(freshExecutor::reloadPlugins)
                .isInstanceOf(CommandException.class)
                .hasCause(cause)
                .extracting(t -> ((CommandException) t).getErrorCode())
                .isEqualTo(ErrorCode.COMMAND_EXECUTION_FAILED);
    }

    @Test
    @DisplayName("#509: /ul reload <name> replies failure, not success, when the module's reload throws")
    void reloadNamedModuleWhoseReloadThrowsRepliesFailure() {
        UltiToolsPlugin broken = mock(UltiToolsPlugin.class);
        when(broken.getPluginName()).thenReturn("BrokenModule");
        doThrow(new IllegalStateException("hook boom")).when(broken).reloadWithReport();
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList(broken));

        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"reload", "BrokenModule"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        String reply = player.nextMessage();
        assertThat(reply).as("the sender is told the reload failed, naming the module")
                .contains("BrokenModule").contains("failed to reload").contains("hook boom")
                .doesNotContain("%s");
        assertThat(player.nextMessage()).as("no success reply follows a failure").isNull();
    }

    @Test
    @DisplayName("#529: /ul reload <name> names the parts that did not reload instead of the plain success reply")
    void reloadNamedModuleWithPartialReportRepliesTheReasons() {
        UltiToolsPlugin partial = mock(UltiToolsPlugin.class);
        when(partial.getPluginName()).thenReturn("PartialModule");
        ReloadReport report = new ReloadReport();
        report.partial("scoreboard service did not restart");
        when(partial.reloadWithReport()).thenReturn(report);
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList(partial));

        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"reload", "PartialModule"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        String reply = player.nextMessage();
        assertThat(reply).contains("PartialModule").contains("scoreboard service did not restart")
                .doesNotContain("%s").isNotEqualTo("模块 PartialModule 已重载");
        assertThat(player.nextMessage()).as("no plain success reply follows").isNull();
    }

    @Test
    @DisplayName("/ul reload <name> replies success for a complete reload")
    void reloadNamedModuleRepliesSuccess() {
        UltiToolsPlugin good = mock(UltiToolsPlugin.class);
        when(good.getPluginName()).thenReturn("GoodModule");
        when(good.reloadWithReport()).thenReturn(new ReloadReport());
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList(good));

        executor.onCommand(player, mockCommand, "ul", new String[]{"reload", "GoodModule"});
        server.getScheduler().performOneTick();

        assertThat(player.nextMessage()).isEqualTo("模块 GoodModule 已重载");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    @DisplayName("#509: /ul reload <name> replies failure for an Error or an undeclared checked exception too")
    void reloadNamedModuleRepliesFailureForErrorsAndSneakyCheckedExceptions() {
        // Gate-1 review (reviewer A P3, reviewer B IN-01/IN-02): the command caught only
        // RuntimeException | Error and rethrew VirtualMachineError, so these reached the generic
        // command-error line instead of the reload's own failure reply.
        UltiToolsPlugin recursive = mock(UltiToolsPlugin.class);
        when(recursive.getPluginName()).thenReturn("RecursiveModule");
        doThrow(new StackOverflowError("recursive onReload")).when(recursive).reloadWithReport();
        UltiToolsPlugin sneaky = mock(UltiToolsPlugin.class);
        when(sneaky.getPluginName()).thenReturn("SneakyModule");
        when(sneaky.reloadWithReport()).thenAnswer(invocation -> {
            sneakyThrow(new IOException("disk gone"));
            return null;
        });
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList(recursive, sneaky));

        executor.onCommand(player, mockCommand, "ul", new String[]{"reload", "RecursiveModule"});
        server.getScheduler().performOneTick();
        executor.onCommand(player, mockCommand, "ul", new String[]{"reload", "SneakyModule"});
        server.getScheduler().performOneTick();

        assertThat(player.nextMessage()).contains("RecursiveModule").contains("failed to reload")
                .contains("recursive onReload");
        assertThat(player.nextMessage()).contains("SneakyModule").contains("failed to reload").contains("disk gone");
    }

    @Test
    @DisplayName("#509: a bare /ul reload replies the reload summary to the sender")
    void bareReloadRepliesTheSummary() throws IOException {
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getPluginManager()).thenReturn(mockPluginManager);
            try {
                when(ultiTools.reloadPluginsAndReport())
                        .thenReturn(Arrays.asList("Failed to reload 1 of 2 modules: BrokenModule."));
            } catch (IOException ignored) {
                // stubbing never invokes the real method; the checked exception cannot occur here
            }
        });
        UltiToolsCommands freshExecutor = new UltiToolsCommands();

        boolean result = freshExecutor.onCommand(player, mockCommand, "ul", new String[]{"reload"});
        server.getScheduler().performOneTick();

        assertThat(result).isTrue();
        assertThat(player.nextMessage()).isEqualTo("Failed to reload 1 of 2 modules: BrokenModule.");
    }

    @Test
    @DisplayName("Should work with console sender")
    void testConsoleCommands() {
        ConsoleCommandSender console = server.getConsoleSender();
        
        when(mockPluginManager.getPluginList()).thenReturn(Arrays.asList());
        
        boolean result = executor.onCommand(console, mockCommand, "ul", new String[]{"list"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle unknown command")
    void testUnknownCommand() {
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"unknown"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("未知");
    }

    @Test
    @DisplayName("Should handle command without OP permission")
    void testNoOpPermission() {
        player.setOp(false);
        
        boolean result = executor.onCommand(player, mockCommand, "ul", new String[]{"reload"});
        server.getScheduler().performOneTick();
        
        assertThat(result).isTrue();
        String message = player.nextMessage();
        assertThat(message).contains("权限");
    }

    @Test
    @DisplayName("Should provide tab completion")
    void testTabCompletion() {
        player.setOp(true);
        
        List<String> completions = executor.onTabComplete(player, mockCommand, "ul", new String[]{""});
        
        assertThat(completions).contains("reload", "help", "list");
    }

    @Test
    @DisplayName("Should handle help command directly")
    void testHandleHelpDirect() {
        executor.handleHelp(player);
        
        String message = player.nextMessage();
        assertThat(message).contains("UltiTools");
        assertThat(message).contains("命令列表");
    }
}

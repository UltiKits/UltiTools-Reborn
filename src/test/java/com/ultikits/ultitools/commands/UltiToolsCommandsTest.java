package com.ultikits.ultitools.commands;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.PluginManager;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

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
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        
        player = server.addPlayer("testplayer");
        player.setOp(true);
        
        mockCommand = mock(Command.class);
        when(mockCommand.getName()).thenReturn("ul");
        
        mockPluginManager = mock(PluginManager.class);
        when(UltiTools.getInstance().getPluginManager()).thenReturn(mockPluginManager);
        
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

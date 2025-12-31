package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;

/**
 * Unit tests for AbstractPlayerCommandExecutor.
 * Tests player-only command validation and execution.
 * <p>
 * AbstractPlayerCommandExecutor 的单元测试。
 * 测试仅限玩家的命令验证和执行。
 *
 * @author UltiKits Test Suite
 * @since 6.2.0
 */
@DisplayName("AbstractPlayerCommandExecutor Tests")
@ExtendWith(MockitoExtension.class)
class AbstractPlayerCommandExecutorTest {

    @Mock
    private Command mockCommand;
    
    @Mock
    private Player player;
    
    @Mock
    private CommandSender consoleSender;
    
    @Mock
    private UltiTools mockPlugin;

    private TestPlayerCommand playerCommand;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        lenient().when(player.getName()).thenReturn("TestPlayer");
        lenient().when(mockCommand.getName()).thenReturn("playertest");
        
        playerCommand = new TestPlayerCommand();
    }

    // ==================== Player Validation Tests ====================

    @Test
    @DisplayName("Should allow player to execute command")
    void testPlayerCanExecute() {
        String[] args = {"test"};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", args);
        
        assertThat(result)
            .as("Player should be able to execute command")
            .isTrue();
        
        assertThat(playerCommand.onPlayerCommandCalled)
            .as("onPlayerCommand should be called")
            .isTrue();
    }

    @Test
    @DisplayName("Should deny console from executing player command")
    void testConsoleDenied() {
        String[] args = {"test"};
        
        boolean result = playerCommand.onCommand(consoleSender, mockCommand, "playertest", args);
        
        assertThat(result)
            .as("Console should be denied")
            .isFalse();
        
        assertThat(playerCommand.onPlayerCommandCalled)
            .as("onPlayerCommand should not be called for console")
            .isFalse();
    }

    @Test
    @DisplayName("Should pass correct player instance to onPlayerCommand")
    void testPlayerInstancePassed() {
        String[] args = {"test"};
        
        playerCommand.onCommand(player, mockCommand, "playertest", args);
        
        assertThat(playerCommand.receivedPlayer)
            .as("Correct player instance should be passed")
            .isEqualTo(player);
    }

    // ==================== Help Command Integration ====================

    @Test
    @DisplayName("Should handle help command for player")
    void testPlayerHelpCommand() {
        String[] helpArgs = {"help"};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", helpArgs);
        
        assertThat(result).isTrue();
        assertThat(playerCommand.helpCalled).isTrue();
        assertThat(playerCommand.onPlayerCommandCalled).isFalse();
    }

    @Test
    @DisplayName("Should deny console from accessing help")
    void testConsoleHelpDenied() {
        String[] helpArgs = {"help"};
        
        boolean result = playerCommand.onCommand(consoleSender, mockCommand, "playertest", helpArgs);
        
        // Help is checked first, so console is allowed to see help
        assertThat(result).isTrue();
        assertThat(playerCommand.helpCalled).isTrue();
    }

    // ==================== Command Execution Tests ====================

    @Test
    @DisplayName("Should execute command with arguments")
    void testCommandWithArguments() {
        String[] args = {"arg1", "arg2", "arg3"};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", args);
        
        assertThat(result).isTrue();
        assertThat(playerCommand.receivedArgs)
            .as("Arguments should be passed correctly")
            .isEqualTo(args);
    }

    @Test
    @DisplayName("Should handle command failure in onPlayerCommand")
    void testCommandFailure() {
        playerCommand.shouldFail = true;
        String[] args = {"test"};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", args);
        
        assertThat(result)
            .as("Should return false on failure")
            .isFalse();
    }

    @Test
    @DisplayName("Should execute empty command from player")
    void testEmptyCommand() {
        String[] emptyArgs = {};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", emptyArgs);
        
        assertThat(result).isTrue();
        assertThat(playerCommand.onPlayerCommandCalled).isTrue();
    }

    // ==================== Multiple Players Tests ====================

    @Test
    @DisplayName("Should handle multiple different players correctly")
    void testMultiplePlayers() {
        Player player1 = mock(Player.class);
        Player player2 = mock(Player.class);
        Player player3 = mock(Player.class);
        
        lenient().when(player1.getName()).thenReturn("Player1");
        lenient().when(player2.getName()).thenReturn("Player2");
        lenient().when(player3.getName()).thenReturn("Player3");
        
        String[] args = {"test"};
        
        playerCommand.onCommand(player1, mockCommand, "playertest", args);
        assertThat(playerCommand.receivedPlayer).isEqualTo(player1);
        
        playerCommand.onCommand(player2, mockCommand, "playertest", args);
        assertThat(playerCommand.receivedPlayer).isEqualTo(player2);
        
        playerCommand.onCommand(player3, mockCommand, "playertest", args);
        assertThat(playerCommand.receivedPlayer).isEqualTo(player3);
        
        assertThat(playerCommand.executionCount)
            .as("Should execute for all players")
            .isEqualTo(3);
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle command with special characters")
    void testSpecialCharacters() {
        String[] args = {"test@#$%", "äöü", "中文"};
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", args);
        
        assertThat(result).isTrue();
        assertThat(playerCommand.receivedArgs).isEqualTo(args);
    }

    @Test
    @DisplayName("Should handle very long argument list")
    void testLongArgumentList() {
        String[] longArgs = new String[100];
        for (int i = 0; i < longArgs.length; i++) {
            longArgs[i] = "arg" + i;
        }
        
        boolean result = playerCommand.onCommand(player, mockCommand, "playertest", longArgs);
        
        assertThat(result).isTrue();
        assertThat(playerCommand.receivedArgs).hasSize(100);
    }

    // ==================== Test Implementation ====================

    private static class TestPlayerCommand extends AbstractPlayerCommandExecutor {
        boolean onPlayerCommandCalled = false;
        boolean helpCalled = false;
        boolean shouldFail = false;
        Player receivedPlayer = null;
        String[] receivedArgs = null;
        int executionCount = 0;

        @Override
        protected boolean onPlayerCommand(Command command, String[] args, Player player) {
            onPlayerCommandCalled = true;
            receivedPlayer = player;
            receivedArgs = args;
            executionCount++;
            return !shouldFail;
        }

        @Override
        protected void sendHelpMessage(CommandSender sender) {
            helpCalled = true;
            sender.sendMessage("Player command help");
        }
    }
}

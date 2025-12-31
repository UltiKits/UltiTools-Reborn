package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;

/**
 * Unit tests for AbstractConsoleCommandExecutor.
 * Tests console-only command validation and execution.
 * <p>
 * AbstractConsoleCommandExecutor 的单元测试。
 * 测试仅限控制台的命令验证和执行。
 *
 * @author UltiKits Test Suite
 * @since 6.2.0
 */
@DisplayName("AbstractConsoleCommandExecutor Tests")
@ExtendWith(MockitoExtension.class)
class AbstractConsoleCommandExecutorTest {

    @Mock
    private Command mockCommand;
    
    @Mock
    private ConsoleCommandSender console;
    
    @Mock
    private Player player;
    
    @Mock
    private UltiTools mockPlugin;

    private TestConsoleCommand consoleCommand;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        lenient().when(mockCommand.getName()).thenReturn("consoletest");
        lenient().when(player.getName()).thenReturn("TestPlayer");
        
        consoleCommand = new TestConsoleCommand();
    }

    // ==================== Console Validation Tests ====================

    @Test
    @DisplayName("Should allow console to execute command")
    void testConsoleCanExecute() {
        String[] args = {"test"};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", args);
        
        assertThat(result)
            .as("Console should be able to execute command")
            .isTrue();
        
        assertThat(consoleCommand.onConsoleCommandCalled)
            .as("onConsoleCommand should be called")
            .isTrue();
    }

    @Test
    @DisplayName("Should deny player from executing console command")
    void testPlayerDenied() {
        String[] args = {"test"};
        
        boolean result = consoleCommand.onCommand(player, mockCommand, "consoletest", args);
        
        assertThat(result)
            .as("Player should be denied")
            .isFalse();
        
        assertThat(consoleCommand.onConsoleCommandCalled)
            .as("onConsoleCommand should not be called for players")
            .isFalse();
    }

    @Test
    @DisplayName("Should pass correct console instance to onConsoleCommand")
    void testConsoleInstancePassed() {
        String[] args = {"test"};
        
        consoleCommand.onCommand(console, mockCommand, "consoletest", args);
        
        assertThat(consoleCommand.receivedSender)
            .as("Correct console instance should be passed")
            .isEqualTo(console);
    }

    // ==================== Help Command Integration ====================

    @Test
    @DisplayName("Should handle help command for console")
    void testConsoleHelpCommand() {
        String[] helpArgs = {"help"};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", helpArgs);
        
        assertThat(result).isTrue();
        assertThat(consoleCommand.helpCalled).isTrue();
        assertThat(consoleCommand.onConsoleCommandCalled).isFalse();
    }

    @Test
    @DisplayName("Should allow player to see help but deny execution")
    void testPlayerHelpAccess() {
        String[] helpArgs = {"help"};
        
        boolean result = consoleCommand.onCommand(player, mockCommand, "consoletest", helpArgs);
        
        // Help is shown before player validation
        assertThat(result).isTrue();
        assertThat(consoleCommand.helpCalled).isTrue();
    }

    // ==================== Command Execution Tests ====================

    @Test
    @DisplayName("Should execute command with arguments")
    void testCommandWithArguments() {
        String[] args = {"arg1", "arg2", "arg3"};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", args);
        
        assertThat(result).isTrue();
        assertThat(consoleCommand.receivedArgs)
            .as("Arguments should be passed correctly")
            .isEqualTo(args);
    }

    @Test
    @DisplayName("Should handle command failure in onConsoleCommand")
    void testCommandFailure() {
        consoleCommand.shouldFail = true;
        String[] args = {"test"};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", args);
        
        assertThat(result)
            .as("Should return false on failure")
            .isFalse();
    }

    @Test
    @DisplayName("Should execute empty command from console")
    void testEmptyCommand() {
        String[] emptyArgs = {};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", emptyArgs);
        
        assertThat(result).isTrue();
        assertThat(consoleCommand.onConsoleCommandCalled).isTrue();
    }

    // ==================== Multiple Players Denied Tests ====================

    @Test
    @DisplayName("Should deny all players from console command")
    void testMultiplePlayersDenied() {
        Player player1 = mock(Player.class);
        Player player2 = mock(Player.class);
        Player player3 = mock(Player.class);
        
        lenient().when(player1.getName()).thenReturn("Player1");
        lenient().when(player2.getName()).thenReturn("Player2");
        lenient().when(player3.getName()).thenReturn("Player3");
        
        String[] args = {"test"};
        
        boolean result1 = consoleCommand.onCommand(player1, mockCommand, "consoletest", args);
        boolean result2 = consoleCommand.onCommand(player2, mockCommand, "consoletest", args);
        boolean result3 = consoleCommand.onCommand(player3, mockCommand, "consoletest", args);
        
        assertThat(result1).isFalse();
        assertThat(result2).isFalse();
        assertThat(result3).isFalse();
        
        assertThat(consoleCommand.executionCount)
            .as("Should not execute for any player")
            .isZero();
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle command with special characters")
    void testSpecialCharacters() {
        String[] args = {"test@#$%", "äöü", "中文"};
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", args);
        
        assertThat(result).isTrue();
        assertThat(consoleCommand.receivedArgs).isEqualTo(args);
    }

    @Test
    @DisplayName("Should handle very long argument list")
    void testLongArgumentList() {
        String[] longArgs = new String[100];
        for (int i = 0; i < longArgs.length; i++) {
            longArgs[i] = "arg" + i;
        }
        
        boolean result = consoleCommand.onCommand(console, mockCommand, "consoletest", longArgs);
        
        assertThat(result).isTrue();
        assertThat(consoleCommand.receivedArgs).hasSize(100);
    }

    @Test
    @DisplayName("Should handle null arguments safely")
    void testNullArguments() {
        assertThatCode(() -> consoleCommand.onCommand(console, mockCommand, "consoletest", null))
            .doesNotThrowAnyException();
    }

    // ==================== Test Implementation ====================

    private static class TestConsoleCommand extends AbstractConsoleCommandExecutor {
        boolean onConsoleCommandCalled = false;
        boolean helpCalled = false;
        boolean shouldFail = false;
        CommandSender receivedSender = null;
        String[] receivedArgs = null;
        int executionCount = 0;

        @Override
        protected boolean onConsoleCommand(CommandSender sender, Command command, String[] args) {
            onConsoleCommandCalled = true;
            receivedSender = sender;
            receivedArgs = args;
            executionCount++;
            return !shouldFail;
        }

        @Override
        protected void sendHelpMessage(CommandSender sender) {
            helpCalled = true;
            sender.sendMessage("Console command help");
        }
    }
}

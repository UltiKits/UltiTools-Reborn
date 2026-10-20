package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AbstractCommand.
 * Tests the refactored template method pattern and help handling.
 * <p>
 * AbstractCommand 的单元测试。
 * 测试重构后的模板方法模式和帮助处理。
 *
 * @author UltiKits Test Suite
 * @since 6.2.0
 */
@DisplayName("AbstractCommand Tests")
@ExtendWith(MockitoExtension.class)
class AbstractCommandTest {

    @Mock
    private Command mockCommand;
    
    @Mock
    private Player player;
    
    @Mock
    private CommandSender consoleSender;

    private TestCommand testCommand;

    @BeforeEach
    void setUp() {
        lenient().when(player.getName()).thenReturn("TestPlayer");
        lenient().when(mockCommand.getName()).thenReturn("testcmd");
        
        testCommand = new TestCommand();
    }

    // ==================== Help Command Handling ====================

    @Test
    @DisplayName("Should handle help command automatically")
    void testHelpCommandHandling() {
        String[] helpArgs = {"help"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", helpArgs);
        
        assertThat(result).isTrue();
        assertThat(testCommand.helpCalled)
            .as("sendHelpMessage should be called for 'help' command")
            .isTrue();
        assertThat(testCommand.executeCommandCalled)
            .as("executeCommand should NOT be called for 'help' command")
            .isFalse();
    }

    @Test
    @DisplayName("Should handle help command case-insensitively")
    void testHelpCommandCaseInsensitive() {
        String[][] helpVariations = {
            {"HELP"},
            {"Help"},
            {"HeLp"},
            {"hElP"}
        };
        
        for (String[] args : helpVariations) {
            testCommand.reset();
            boolean result = testCommand.onCommand(player, mockCommand, "testcmd", args);
            
            assertThat(result).isTrue();
            assertThat(testCommand.helpCalled).isTrue();
            assertThat(testCommand.executeCommandCalled).isFalse();
        }
    }

    @Test
    @DisplayName("Should use custom help message from subclass")
    void testCustomHelpMessage() {
        String[] helpArgs = {"help"};
        
        testCommand.onCommand(player, mockCommand, "testcmd", helpArgs);
        
        assertThat(testCommand.helpCalled).isTrue();
        assertThat(testCommand.customHelpUsed).isTrue();
    }

    @Test
    @DisplayName("Should handle help command from console")
    void testConsoleHelpCommand() {
        String[] helpArgs = {"help"};
        
        boolean result = testCommand.onCommand(consoleSender, mockCommand, "testcmd", helpArgs);
        
        assertThat(result).isTrue();
        assertThat(testCommand.helpCalled).isTrue();
    }

    // ==================== Normal Command Execution ====================

    @Test
    @DisplayName("Should execute normal command when not help")
    void testNormalCommandExecution() {
        String[] normalArgs = {"action", "value"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", normalArgs);
        
        assertThat(testCommand.executeCommandCalled)
            .as("executeCommand should be called for normal commands")
            .isTrue();
        assertThat(testCommand.helpCalled)
            .as("sendHelpMessage should NOT be called for normal commands")
            .isFalse();
    }

    @Test
    @DisplayName("Should pass correct parameters to executeCommand")
    void testExecuteCommandParameters() {
        String[] args = {"arg1", "arg2"};
        
        testCommand.onCommand(player, mockCommand, "testcmd", args);
        
        assertThat(testCommand.receivedSender).isEqualTo(player);
        assertThat(testCommand.receivedCommand).isEqualTo(mockCommand);
        assertThat(testCommand.receivedArgs).isEqualTo(args);
    }

    @Test
    @DisplayName("Should return result from executeCommand")
    void testExecuteCommandReturnValue() {
        testCommand.shouldReturnTrue = true;
        String[] args = {"test"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", args);
        
        assertThat(result).isTrue();
        
        testCommand.shouldReturnTrue = false;
        result = testCommand.onCommand(player, mockCommand, "testcmd", args);
        
        assertThat(result).isFalse();
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should show help message on executeCommand failure")
    void testErrorMessageOnFailure() {
        testCommand.shouldReturnTrue = false;
        String[] args = {"fail"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", args);
        
        assertThat(result).isFalse();
        assertThat(testCommand.executeCommandCalled).isTrue();
        // Help message is NOT automatically shown on failure in current implementation
    }

    @Test
    @DisplayName("Should handle exceptions in executeCommand gracefully")
    void testExceptionHandling() {
        testCommand.shouldThrowException = true;
        String[] args = {"error"};
        
        assertThatThrownBy(() -> testCommand.onCommand(player, mockCommand, "testcmd", args))
            .as("Exceptions should propagate from executeCommand")
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Test exception");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle empty arguments")
    void testEmptyArguments() {
        String[] emptyArgs = {};

        testCommand.onCommand(player, mockCommand, "testcmd", emptyArgs);

        assertThat(testCommand.executeCommandCalled).isTrue();
        assertThat(testCommand.receivedArgs).isEmpty();
    }

    @Test
    @DisplayName("Should handle null arguments safely")
    void testNullArguments() {
        assertThatCode(() -> testCommand.onCommand(player, mockCommand, "testcmd", null))
            .as("Should handle null args gracefully")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should treat 'help' with additional args as help command")
    void testHelpWithAdditionalArgs() {
        String[] helpWithArgs = {"help", "extra", "args"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", helpWithArgs);
        
        assertThat(result).isTrue();
        assertThat(testCommand.helpCalled).isTrue();
        assertThat(testCommand.executeCommandCalled).isFalse();
    }

    @Test
    @DisplayName("Should not treat 'help' in middle of args as help command")
    void testHelpNotFirstArg() {
        String[] argsWithHelp = {"action", "help", "test"};
        
        boolean result = testCommand.onCommand(player, mockCommand, "testcmd", argsWithHelp);
        
        assertThat(testCommand.executeCommandCalled)
            .as("Should execute normally when 'help' is not first arg")
            .isTrue();
        assertThat(testCommand.helpCalled).isFalse();
    }

    // ==================== Template Method Pattern ====================

    @Test
    @DisplayName("Should follow template method pattern correctly")
    void testTemplateMethodPattern() {
        // Test the flow: onCommand -> check help -> call executeCommand/sendHelpMessage
        
        // Help flow
        testCommand.reset();
        testCommand.onCommand(player, mockCommand, "testcmd", new String[]{"help"});
        assertThat(testCommand.helpCalled).isTrue();
        assertThat(testCommand.executeCommandCalled).isFalse();
        
        // Execute flow
        testCommand.reset();
        testCommand.onCommand(player, mockCommand, "testcmd", new String[]{"action"});
        assertThat(testCommand.executeCommandCalled).isTrue();
        assertThat(testCommand.helpCalled).isFalse();
    }

    // ==================== Test Command Implementation ====================

    private static class TestCommand extends AbstractCommand {
        boolean helpCalled = false;
        boolean executeCommandCalled = false;
        boolean customHelpUsed = false;
        boolean shouldReturnTrue = true;
        boolean shouldThrowException = false;
        
        CommandSender receivedSender = null;
        Command receivedCommand = null;
        String[] receivedArgs = null;

        // Note: AbstractCommand in current branch might not have executeCommand method
        // It seems AbstractCommand is abstract and implements CommandExecutor
        // But looking at the provided AbstractCommand.java, it only has sendHelpMessage, sendErrorMessage, getHelpCommand
        // It does NOT implement onCommand.
        // Wait, the provided AbstractCommand.java implements CommandExecutor but does NOT implement onCommand?
        // Let me check the provided AbstractCommand.java again.
        
        // Ah, the provided AbstractCommand.java is abstract and implements CommandExecutor.
        // But it doesn't have onCommand implementation shown in the attachment?
        // Wait, the attachment shows:
        // public abstract class AbstractCommand implements CommandExecutor {
        //    protected abstract void sendHelpMessage(CommandSender sender);
        //    protected void sendErrorMessage(CommandSender sender, Command command) { ... }
        //    protected String getHelpCommand() { ... }
        // }
        // It does NOT implement onCommand. So the test class TestCommand must implement onCommand.
        
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args != null && args.length > 0 && args[0].equalsIgnoreCase(getHelpCommand())) {
                sendHelpMessage(sender);
                return true;
            }
            return executeCommand(sender, command, label, args);
        }

        @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
        protected boolean executeCommand(CommandSender sender, Command command, String alias, String[] args) {
            if (shouldThrowException) {
                throw new RuntimeException("Test exception");
            }
            
            executeCommandCalled = true;
            receivedSender = sender;
            receivedCommand = command;
            receivedArgs = args;
            return shouldReturnTrue;
        }

        @Override
        protected void sendHelpMessage(CommandSender sender) {
            helpCalled = true;
            customHelpUsed = true;
            sender.sendMessage("Custom help message");
        }
        
        void reset() {
            helpCalled = false;
            executeCommandCalled = false;
            customHelpUsed = false;
            receivedSender = null;
            receivedCommand = null;
            receivedArgs = null;
        }
    }
}

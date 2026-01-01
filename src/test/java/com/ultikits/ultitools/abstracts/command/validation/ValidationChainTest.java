package com.ultikits.ultitools.abstracts.command.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Unit tests for validation chain and validators.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Validation Chain Tests")
class ValidationChainTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private ConsoleCommandSender mockConsole;
    
    @Mock
    private Command mockCommand;
    
    private CommandContext playerContext;
    private CommandContext consoleContext;

    @BeforeEach
    void setUp() {
        lenient().when(mockCommand.getName()).thenReturn("test");
        
        playerContext = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        consoleContext = CommandContext.builder()
                .sender(mockConsole)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
    }

    @Nested
    @DisplayName("SenderTypeValidator Tests")
    class SenderTypeValidatorTests {
        
        @Test
        @DisplayName("Should pass for PLAYER type when sender is Player")
        void shouldPassForPlayerType() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            CommandValidator.ValidationResult result = validator.validate(playerContext);
            assertTrue(result.isValid());
        }
        
        @Test
        @DisplayName("Should pass for CONSOLE type when sender is Console")
        void shouldPassForConsoleType() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            CommandValidator.ValidationResult result = validator.validate(consoleContext);
            assertTrue(result.isValid());
        }
        
        @Test
        @DisplayName("Should pass for BOTH type for any sender")
        void shouldPassForBothType() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            
            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }
        
        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            assertEquals(100, validator.getOrder());
        }
    }

    @Nested
    @DisplayName("PermissionValidator Tests")
    class PermissionValidatorTests {
        
        @Test
        @DisplayName("Should pass when sender has permission")
        void shouldPassWhenHasPermission() {
            when(mockPlayer.hasPermission("test.permission")).thenReturn(true);
            
            PermissionValidator validator = new PermissionValidator("test.permission", false);
            CommandValidator.ValidationResult result = validator.validate(playerContext);
            assertTrue(result.isValid());
        }
        
        @Test
        @DisplayName("Should pass when requireOp and sender is OP")
        void shouldPassWhenIsOp() {
            when(mockPlayer.isOp()).thenReturn(true);
            
            PermissionValidator validator = new PermissionValidator("", true);
            CommandValidator.ValidationResult result = validator.validate(playerContext);
            assertTrue(result.isValid());
        }
        
        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            PermissionValidator validator = new PermissionValidator("test", false);
            assertEquals(200, validator.getOrder());
        }
    }

    @Nested
    @DisplayName("ValidatorChain Tests")
    class ChainTests {
        
        @Test
        @DisplayName("Should execute validators in order")
        void shouldExecuteInOrder() {
            when(mockPlayer.hasPermission(anyString())).thenReturn(true);
            
            ValidatorChain chain = ValidatorChain.builder()
                    .add(new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER))
                    .add(new PermissionValidator("test.perm", false))
                    .build();
            
            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertTrue(result.isValid());
        }
        
        @Test
        @DisplayName("Empty chain should pass")
        void emptyChainShouldPass() {
            ValidatorChain chain = ValidatorChain.builder().build();
            
            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("ValidationResult Tests")
    class ValidationResultTests {
        
        @Test
        @DisplayName("Success result should be valid")
        void successShouldBeValid() {
            CommandValidator.ValidationResult result = CommandValidator.ValidationResult.success();
            assertTrue(result.isValid());
            assertNull(result.getErrorMessage());
        }
        
        @Test
        @DisplayName("Failure result should not be valid")
        void failureShouldNotBeValid() {
            CommandValidator.ValidationResult result = 
                    CommandValidator.ValidationResult.failure("Error message");
            assertFalse(result.isValid());
            assertEquals("Error message", result.getErrorMessage());
        }
    }
}

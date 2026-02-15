package com.ultikits.ultitools.abstracts.command.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Comprehensive unit tests for validation chain and validators.
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

    @Mock
    private UltiTools mockUltiTools;

    private MockedStatic<UltiTools> mockedUltiTools;
    
    private CommandContext playerContext;
    private CommandContext consoleContext;

    @BeforeEach
    void setUp() {
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
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

    @AfterEach
    void tearDown() {
        if (mockedUltiTools != null) {
            mockedUltiTools.close();
        }
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

        @Test
        @DisplayName("Failure result with error key should store key")
        void failureWithErrorKeyShouldStoreKey() {
            CommandValidator.ValidationResult result = 
                    CommandValidator.ValidationResult.failure("Error message", "error.key");
            assertFalse(result.isValid());
            assertEquals("Error message", result.getErrorMessage());
            assertEquals("error.key", result.getErrorKey());
        }

        @Test
        @DisplayName("Success result should have null error key")
        void successShouldHaveNullErrorKey() {
            CommandValidator.ValidationResult result = CommandValidator.ValidationResult.success();
            assertNull(result.getErrorKey());
        }
    }

    @Nested
    @DisplayName("ValidatorChain Advanced Tests")
    class ChainAdvancedTests {

        @Test
        @DisplayName("Should stop at first failure in validate()")
        void shouldStopAtFirstFailure() {
            SenderTypeValidator validator1 = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            PermissionValidator validator2 = new PermissionValidator("test.perm", false);

            ValidatorChain chain = ValidatorChain.builder()
                    .add(validator1)
                    .add(validator2)
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertFalse(result.isValid());
            assertEquals("SenderTypeValidator", result.getFailedValidator().getName());
            // Should only have one result since chain stops at first failure
            assertEquals(1, result.getAllResults().size());
        }

        @Test
        @DisplayName("validateAll should collect all failures")
        void validateAllShouldCollectAllFailures() {
            when(mockPlayer.hasPermission(anyString())).thenReturn(false);
            
            SenderTypeValidator validator1 = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            PermissionValidator validator2 = new PermissionValidator("test.perm", false);

            ValidatorChain chain = ValidatorChain.builder()
                    .add(validator1)
                    .add(validator2)
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validateAll(playerContext);
            assertFalse(result.isValid());
            assertEquals(2, result.getAllFailedValidators().size());
            assertEquals(2, result.getAllResults().size());
        }

        @Test
        @DisplayName("validateAll should pass when all validators pass")
        void validateAllShouldPassWhenAllPass() {
            when(mockPlayer.hasPermission(anyString())).thenReturn(true);

            ValidatorChain chain = ValidatorChain.builder()
                    .add(new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER))
                    .add(new PermissionValidator("test.perm", false))
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validateAll(playerContext);
            assertTrue(result.isValid());
            assertTrue(result.getAllFailedValidators().isEmpty());
        }

        @Test
        @DisplayName("Should sort validators by order")
        void shouldSortValidatorsByOrder() {
            // PermissionValidator has order 200, SenderTypeValidator has order 100
            PermissionValidator permValidator = new PermissionValidator();
            SenderTypeValidator senderValidator = new SenderTypeValidator();

            // Add in reverse order
            ValidatorChain chain = ValidatorChain.builder()
                    .add(permValidator)
                    .add(senderValidator)
                    .build();

            java.util.List<CommandValidator> validators = chain.getValidators();
            assertEquals(2, validators.size());
            // SenderTypeValidator (100) should be first
            assertEquals("SenderTypeValidator", validators.get(0).getName());
            // PermissionValidator (200) should be second
            assertEquals("PermissionValidator", validators.get(1).getName());
        }

        @Test
        @DisplayName("addValidator should mark chain as unsorted")
        void addValidatorShouldMarkAsUnsorted() {
            ValidatorChain chain = new ValidatorChain();
            chain.addValidator(new PermissionValidator());
            chain.addValidator(new SenderTypeValidator());

            // Getting validators should trigger sort
            java.util.List<CommandValidator> validators = chain.getValidators();
            assertEquals("SenderTypeValidator", validators.get(0).getName());
        }

        @Test
        @DisplayName("removeValidator should work correctly")
        void removeValidatorShouldWork() {
            SenderTypeValidator validator = new SenderTypeValidator();
            ValidatorChain chain = new ValidatorChain();
            chain.addValidator(validator);
            assertEquals(1, chain.size());

            chain.removeValidator(validator);
            assertEquals(0, chain.size());
            assertTrue(chain.isEmpty());
        }

        @Test
        @DisplayName("removeValidators by class should work")
        void removeValidatorsByClassShouldWork() {
            ValidatorChain chain = new ValidatorChain();
            chain.addValidator(new SenderTypeValidator());
            chain.addValidator(new SenderTypeValidator());
            chain.addValidator(new PermissionValidator());
            assertEquals(3, chain.size());

            chain.removeValidators(SenderTypeValidator.class);
            assertEquals(1, chain.size());
        }

        @Test
        @DisplayName("clear should remove all validators")
        void clearShouldRemoveAllValidators() {
            ValidatorChain chain = new ValidatorChain();
            chain.addValidator(new SenderTypeValidator());
            chain.addValidator(new PermissionValidator());
            assertEquals(2, chain.size());

            chain.clear();
            assertEquals(0, chain.size());
            assertTrue(chain.isEmpty());
        }

        @Test
        @DisplayName("addValidator should throw on null")
        void addValidatorShouldThrowOnNull() {
            ValidatorChain chain = new ValidatorChain();
            assertThrows(NullPointerException.class, () -> chain.addValidator(null));
        }

        @Test
        @DisplayName("Constructor with collection should work")
        void constructorWithCollectionShouldWork() {
            java.util.List<CommandValidator> validators = java.util.Arrays.asList(
                    new PermissionValidator(),
                    new SenderTypeValidator()
            );

            ValidatorChain chain = new ValidatorChain(validators);
            assertEquals(2, chain.size());
            // Should be sorted
            assertEquals("SenderTypeValidator", chain.getValidators().get(0).getName());
        }

        @Test
        @DisplayName("Builder addAll should work")
        void builderAddAllShouldWork() {
            java.util.List<CommandValidator> validators = java.util.Arrays.asList(
                    new PermissionValidator(),
                    new SenderTypeValidator()
            );

            ValidatorChain chain = ValidatorChain.builder()
                    .addAll(validators)
                    .build();

            assertEquals(2, chain.size());
        }
    }

    @Nested
    @DisplayName("ChainValidationResult Tests")
    class ChainValidationResultTests {

        @Test
        @DisplayName("Success result should have empty failed validators list")
        void successShouldHaveEmptyFailedList() {
            ValidatorChain chain = ValidatorChain.builder().build();
            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);

            assertTrue(result.isValid());
            assertTrue(result.getAllFailedValidators().isEmpty());
            assertNull(result.getFailedValidator());
            assertNull(result.getFailedResult());
        }

        @Test
        @DisplayName("getErrorMessage should return first failure message")
        void getErrorMessageShouldReturnFirstFailure() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);

            ValidatorChain chain = ValidatorChain.builder()
                    .add(validator)
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertFalse(result.isValid());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("getErrorMessage should return null for success")
        void getErrorMessageShouldReturnNullForSuccess() {
            ValidatorChain chain = ValidatorChain.builder()
                    .add(new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER))
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertTrue(result.isValid());
            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("Multiple failures should have correct first failed validator")
        void multipleFailuresShouldHaveCorrectFirstFailed() {
            when(mockPlayer.hasPermission(anyString())).thenReturn(false);

            ValidatorChain chain = ValidatorChain.builder()
                    .add(new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE))
                    .add(new PermissionValidator("test.perm", false))
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validateAll(playerContext);
            assertFalse(result.isValid());
            assertEquals("SenderTypeValidator", result.getFailedValidator().getName());
            assertEquals(2, result.getAllFailedValidators().size());
        }
    }

    @Nested
    @DisplayName("CommandValidator Interface Tests")
    class CommandValidatorInterfaceTests {

        @Test
        @DisplayName("Default shouldValidate should return true")
        void defaultShouldValidateShouldReturnTrue() {
            CommandValidator validator = new SenderTypeValidator();
            assertTrue(validator.shouldValidate(playerContext));
        }

        @Test
        @DisplayName("Default getName should return class simple name")
        void defaultGetNameShouldReturnClassName() {
            CommandValidator validator = new CommandValidator() {
                @Override
                public CommandValidator.ValidationResult validate(CommandContext context) {
                    return CommandValidator.ValidationResult.success();
                }
            };
            // Anonymous class will have a different name
            assertNotNull(validator.getName());
        }

        @Test
        @DisplayName("Default getOrder should return 0")
        void defaultGetOrderShouldReturnZero() {
            CommandValidator validator = context -> CommandValidator.ValidationResult.success();
            assertEquals(0, validator.getOrder());
        }

        @Test
        @DisplayName("Validator with shouldValidate=false should be skipped")
        void validatorWithShouldValidateFalseShouldBeSkipped() {
            CommandValidator skippedValidator = new CommandValidator() {
                @Override
                public ValidationResult validate(CommandContext context) {
                    return ValidationResult.failure("Should not be called");
                }

                @Override
                public boolean shouldValidate(CommandContext context) {
                    return false;
                }

                @Override
                public String getName() {
                    return "SkippedValidator";
                }
            };

            ValidatorChain chain = ValidatorChain.builder()
                    .add(skippedValidator)
                    .build();

            ValidatorChain.ChainValidationResult result = chain.validate(playerContext);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Fluent API Tests")
    class FluentApiTests {

        @Test
        @DisplayName("Chain operations should return chain for fluent chaining")
        void chainOperationsShouldReturnChain() {
            ValidatorChain chain = new ValidatorChain();
            SenderTypeValidator validator = new SenderTypeValidator();

            assertSame(chain, chain.addValidator(validator));
            assertSame(chain, chain.removeValidator(validator));
            chain.addValidator(validator);
            assertSame(chain, chain.removeValidators(SenderTypeValidator.class));
            chain.addValidator(validator);
            assertSame(chain, chain.clear());
        }
    }
}

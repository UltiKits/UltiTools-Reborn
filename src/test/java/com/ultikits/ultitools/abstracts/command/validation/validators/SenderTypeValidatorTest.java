package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

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
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * Comprehensive unit tests for SenderTypeValidator.
 * Tests sender type validation for PLAYER, CONSOLE, and BOTH target types.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SenderTypeValidator Tests")
class SenderTypeValidatorTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private ConsoleCommandSender mockConsole;

    @Mock
    private Command mockCommand;

    @Mock
    private UltiTools mockUltiTools;

    private MockedStatic<UltiTools> mockedUltiTools;

    @BeforeEach
    void setUp() {
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(mockCommand.getName()).thenReturn("test");
    }

    @AfterEach
    void tearDown() {
        if (mockedUltiTools != null) {
            mockedUltiTools.close();
        }
    }

    // Test methods with @CmdTarget annotation for testing
    @CmdTarget(CmdTarget.CmdTargetType.PLAYER)
    public void methodForPlayerOnly() {}

    @CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
    public void methodForConsoleOnly() {}

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    public void methodForBoth() {}

    public void methodWithoutCmdTarget() {}

    private CommandContext createPlayerContext(Method method) {
        return CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .matchedMethod(method)
                .build();
    }

    private CommandContext createConsoleContext(Method method) {
        return CommandContext.builder()
                .sender(mockConsole)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .matchedMethod(method)
                .build();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor accepts any sender type")
        void defaultConstructorAcceptsAny() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator();
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Constructor with PLAYER type restricts to players")
        void constructorWithPlayerType() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertFalse(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Constructor with CONSOLE type restricts to console")
        void constructorWithConsoleType() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertFalse(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Constructor with BOTH type accepts any sender")
        void constructorWithBothType() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }
    }

    @Nested
    @DisplayName("PLAYER Type Validation Tests")
    class PlayerTypeTests {

        @Test
        @DisplayName("PLAYER type should pass for Player sender")
        void playerTypeShouldPassForPlayer() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("PLAYER type should fail for Console sender")
        void playerTypeShouldFailForConsole() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.player_only", result.getErrorKey());
            assertTrue(result.getErrorMessage().contains("游戏内"));
        }
    }

    @Nested
    @DisplayName("CONSOLE Type Validation Tests")
    class ConsoleTypeTests {

        @Test
        @DisplayName("CONSOLE type should pass for Console sender")
        void consoleTypeShouldPassForConsole() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("CONSOLE type should fail for Player sender")
        void consoleTypeShouldFailForPlayer() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.console_only", result.getErrorKey());
            assertTrue(result.getErrorMessage().contains("后台"));
        }
    }

    @Nested
    @DisplayName("BOTH Type Validation Tests")
    class BothTypeTests {

        @Test
        @DisplayName("BOTH type should pass for Player sender")
        void bothTypeShouldPassForPlayer() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("BOTH type should pass for Console sender")
        void bothTypeShouldPassForConsole() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Method-Level Annotation Tests")
    class MethodLevelAnnotationTests {

        @Test
        @DisplayName("Method-level PLAYER annotation should override class-level BOTH")
        void methodPlayerAnnotationShouldOverride() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodForPlayerOnly");
            
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertFalse(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Method-level CONSOLE annotation should override class-level BOTH")
        void methodConsoleAnnotationShouldOverride() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodForConsoleOnly");
            
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertFalse(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Method-level BOTH annotation should override class-level PLAYER")
        void methodBothAnnotationShouldOverride() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodForBoth");
            
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Method without annotation should use class-level setting")
        void methodWithoutAnnotationShouldUseClassLevel() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertFalse(validator.validate(consoleContext).isValid());
        }
    }

    @Nested
    @DisplayName("Null Method Tests")
    class NullMethodTests {

        @Test
        @DisplayName("Null method should use configured type")
        void nullMethodShouldUseConfiguredType() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            CommandContext playerContext = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();
            CommandContext consoleContext = CommandContext.builder()
                    .sender(mockConsole)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            assertTrue(validator.validate(playerContext).isValid());
            assertFalse(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Null method with BOTH should accept any sender")
        void nullMethodWithBothShouldAcceptAny() {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.BOTH);
            CommandContext playerContext = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();
            CommandContext consoleContext = CommandContext.builder()
                    .sender(mockConsole)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }
    }

    @Nested
    @DisplayName("fromAnnotation Factory Tests")
    class FromAnnotationTests {

        @Test
        @DisplayName("fromAnnotation with null should return default validator")
        void fromAnnotationWithNullShouldReturnDefault() throws Exception {
            SenderTypeValidator validator = SenderTypeValidator.fromAnnotation(null);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            // Default should accept both
            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("fromAnnotation with PLAYER annotation should create PLAYER validator")
        void fromAnnotationWithPlayerShouldCreatePlayerValidator() throws Exception {
            CmdTarget mockAnnotation = mock(CmdTarget.class);
            when(mockAnnotation.value()).thenReturn(CmdTarget.CmdTargetType.PLAYER);

            SenderTypeValidator validator = SenderTypeValidator.fromAnnotation(mockAnnotation);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertFalse(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("fromAnnotation with CONSOLE annotation should create CONSOLE validator")
        void fromAnnotationWithConsoleShouldCreateConsoleValidator() throws Exception {
            CmdTarget mockAnnotation = mock(CmdTarget.class);
            when(mockAnnotation.value()).thenReturn(CmdTarget.CmdTargetType.CONSOLE);

            SenderTypeValidator validator = SenderTypeValidator.fromAnnotation(mockAnnotation);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertFalse(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("fromAnnotation with BOTH annotation should create BOTH validator")
        void fromAnnotationWithBothShouldCreateBothValidator() throws Exception {
            CmdTarget mockAnnotation = mock(CmdTarget.class);
            when(mockAnnotation.value()).thenReturn(CmdTarget.CmdTargetType.BOTH);

            SenderTypeValidator validator = SenderTypeValidator.fromAnnotation(mockAnnotation);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext playerContext = createPlayerContext(method);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid());
            assertTrue(validator.validate(consoleContext).isValid());
        }
    }

    @Nested
    @DisplayName("Order and Name Tests")
    class OrderAndNameTests {

        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            SenderTypeValidator validator = new SenderTypeValidator();
            assertEquals(100, validator.getOrder());
        }

        @Test
        @DisplayName("Should have correct name")
        void shouldHaveCorrectName() {
            SenderTypeValidator validator = new SenderTypeValidator();
            assertEquals("SenderTypeValidator", validator.getName());
        }
    }

    @Nested
    @DisplayName("Error Message Tests")
    class ErrorMessageTests {

        @Test
        @DisplayName("PLAYER type failure message should be descriptive")
        void playerTypeFailureMessageShouldBeDescriptive() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().length() > 0);
        }

        @Test
        @DisplayName("CONSOLE type failure message should be descriptive")
        void consoleTypeFailureMessageShouldBeDescriptive() throws Exception {
            SenderTypeValidator validator = new SenderTypeValidator(CmdTarget.CmdTargetType.CONSOLE);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdTarget");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertNotNull(result.getErrorMessage());
            assertTrue(result.getErrorMessage().length() > 0);
        }
    }
}

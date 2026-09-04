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
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;

/**
 * Comprehensive unit tests for PermissionValidator.
 * Tests permission validation for both class-level and method-level requirements.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionValidator Tests")
class PermissionValidatorTest {

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

    // Test methods with @CmdMapping annotation for testing
    @CmdMapping(format = "test", permission = "test.method.permission")
    public void methodWithPermission() {}

    @CmdMapping(format = "test", permission = "")
    public void methodWithEmptyPermission() {
        // Intentionally empty - used for annotation testing
    }

    @CmdMapping(format = "test", requireOp = true)
    public void methodRequiringOp() {
        // Intentionally empty - used for annotation testing
    }

    @CmdMapping(format = "test", permission = "test.perm", requireOp = true)
    public void methodWithPermissionAndOp() {
        // Intentionally empty - used for annotation testing
    }

    @CmdMapping(format = "test")
    public void methodWithDefaultMapping() {
        // Intentionally empty - used for annotation testing
    }

    public void methodWithoutCmdMapping() {
        // Intentionally empty - used for annotation testing
    }

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
        @DisplayName("Default constructor creates validator with no permission requirement")
        void defaultConstructorNoPermission() throws Exception {
            PermissionValidator validator = new PermissionValidator();
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Constructor with null permission should work")
        void constructorWithNullPermission() throws Exception {
            PermissionValidator validator = new PermissionValidator(null, false);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Constructor with empty permission should work")
        void constructorWithEmptyPermission() throws Exception {
            PermissionValidator validator = new PermissionValidator("", false);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Constructor with permission sets value correctly")
        void constructorWithPermission() {
            PermissionValidator validator = new PermissionValidator("test.perm", false);
            assertEquals("PermissionValidator", validator.getName());
            assertEquals(200, validator.getOrder());
        }
    }

    @Nested
    @DisplayName("Base Permission Tests")
    class BasePermissionTests {

        @Test
        @DisplayName("Should pass when sender has base permission")
        void shouldPassWithBasePermission() throws Exception {
            when(mockPlayer.hasPermission("test.base")).thenReturn(true);

            PermissionValidator validator = new PermissionValidator("test.base", false);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when sender lacks base permission")
        void shouldFailWithoutBasePermission() throws Exception {
            when(mockPlayer.hasPermission("test.base")).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("test.base", false);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.no_permission", result.getErrorKey());
        }

        @Test
        @DisplayName("Console should be checked for permission too")
        void consoleShouldBeCheckedForPermission() throws Exception {
            when(mockConsole.hasPermission("test.base")).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("test.base", false);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
        }
    }

    @Nested
    @DisplayName("OP Requirement Tests")
    class OpRequirementTests {

        @Test
        @DisplayName("Should pass when requireOp and sender is OP")
        void shouldPassWhenSenderIsOp() throws Exception {
            when(mockPlayer.isOp()).thenReturn(true);

            PermissionValidator validator = new PermissionValidator("", true);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when requireOp and sender is not OP")
        void shouldFailWhenSenderIsNotOp() throws Exception {
            when(mockPlayer.isOp()).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("", true);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.op_required", result.getErrorKey());
        }

        @Test
        @DisplayName("Console OP check should work")
        void consoleOpCheckShouldWork() throws Exception {
            when(mockConsole.isOp()).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("", true);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.op_required", result.getErrorKey());
        }
    }

    @Nested
    @DisplayName("Method-Level Permission Tests")
    class MethodLevelPermissionTests {

        @Test
        @DisplayName("Should check method-level permission from CmdMapping")
        void shouldCheckMethodLevelPermission() throws Exception {
            when(mockPlayer.hasPermission("test.method.permission")).thenReturn(true);

            PermissionValidator validator = new PermissionValidator();
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithPermission");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when lacking method-level permission")
        void shouldFailWithoutMethodLevelPermission() throws Exception {
            when(mockPlayer.hasPermission("test.method.permission")).thenReturn(false);

            PermissionValidator validator = new PermissionValidator();
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithPermission");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.no_permission", result.getErrorKey());
        }

        @Test
        @DisplayName("Empty method-level permission should pass")
        void emptyMethodPermissionShouldPass() throws Exception {
            PermissionValidator validator = new PermissionValidator();
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithEmptyPermission");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Method-Level OP Tests")
    class MethodLevelOpTests {

        @Test
        @DisplayName("Should check method-level requireOp from CmdMapping")
        void shouldCheckMethodLevelRequireOp() throws Exception {
            when(mockPlayer.isOp()).thenReturn(true);

            PermissionValidator validator = new PermissionValidator();
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodRequiringOp");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail method-level requireOp when not OP")
        void shouldFailMethodLevelRequireOpWhenNotOp() throws Exception {
            when(mockPlayer.isOp()).thenReturn(false);

            PermissionValidator validator = new PermissionValidator();
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodRequiringOp");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.op_required", result.getErrorKey());
        }
    }

    @Nested
    @DisplayName("Combined Permission and OP Tests")
    class CombinedTests {

        @Test
        @DisplayName("Should pass with both base and method permission and OP")
        void shouldPassWithAllRequirements() throws Exception {
            when(mockPlayer.isOp()).thenReturn(true);
            when(mockPlayer.hasPermission("base.perm")).thenReturn(true);
            when(mockPlayer.hasPermission("test.perm")).thenReturn(true);

            PermissionValidator validator = new PermissionValidator("base.perm", true);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithPermissionAndOp");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail base OP check before method OP check")
        void shouldFailBaseOpFirst() throws Exception {
            when(mockPlayer.isOp()).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("base.perm", true);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithPermissionAndOp");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.op_required", result.getErrorKey());
        }

        @Test
        @DisplayName("Should fail base permission check before method permission check")
        void shouldFailBasePermissionFirst() throws Exception {
            when(mockPlayer.isOp()).thenReturn(true);
            when(mockPlayer.hasPermission("base.perm")).thenReturn(false);

            PermissionValidator validator = new PermissionValidator("base.perm", false);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithPermissionAndOp");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.no_permission", result.getErrorKey());
        }
    }

    @Nested
    @DisplayName("Null Method Tests")
    class NullMethodTests {

        @Test
        @DisplayName("Should handle null matched method for base permission")
        void shouldHandleNullMethodWithBasePermission() {
            when(mockPlayer.hasPermission("base.perm")).thenReturn(true);

            PermissionValidator validator = new PermissionValidator("base.perm", false);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should handle null matched method for OP requirement")
        void shouldHandleNullMethodWithOpRequirement() {
            when(mockPlayer.isOp()).thenReturn(true);

            PermissionValidator validator = new PermissionValidator("", true);
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("fromAnnotation Factory Tests")
    class FromAnnotationTests {

        @Test
        @DisplayName("fromAnnotation with null should return default validator")
        void fromAnnotationWithNullShouldReturnDefault() throws Exception {
            PermissionValidator validator = PermissionValidator.fromAnnotation(null);
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCmdMapping");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("fromAnnotation should create validator from CmdExecutor")
        void fromAnnotationShouldCreateFromCmdExecutor() {
            CmdExecutor mockAnnotation = mock(CmdExecutor.class);
            when(mockAnnotation.permission()).thenReturn("executor.permission");
            when(mockAnnotation.requireOp()).thenReturn(true);

            PermissionValidator validator = PermissionValidator.fromAnnotation(mockAnnotation);

            when(mockPlayer.isOp()).thenReturn(true);
            when(mockPlayer.hasPermission("executor.permission")).thenReturn(true);

            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("fromAnnotation with empty permission should work")
        void fromAnnotationWithEmptyPermissionShouldWork() {
            CmdExecutor mockAnnotation = mock(CmdExecutor.class);
            when(mockAnnotation.permission()).thenReturn("");
            when(mockAnnotation.requireOp()).thenReturn(false);

            PermissionValidator validator = PermissionValidator.fromAnnotation(mockAnnotation);

            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Order and Name Tests")
    class OrderAndNameTests {

        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            PermissionValidator validator = new PermissionValidator();
            assertEquals(200, validator.getOrder());
        }

        @Test
        @DisplayName("Should have correct name")
        void shouldHaveCorrectName() {
            PermissionValidator validator = new PermissionValidator();
            assertEquals("PermissionValidator", validator.getName());
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("#383: who actually reaches the class-level check")
    class ClassLevelReachability {

        /**
         * The class-level branch is not dead code -- the console reaches it. This test exists so
         * that stays true, because the branch looks unreachable if you only consider players.
         */
        @Test
        @DisplayName("the console does reach the class-level check and can be refused by it")
        void consoleReachesTheClassLevelCheck() {
            when(mockConsole.hasPermission("some.permission")).thenReturn(false);
            PermissionValidator validator = new PermissionValidator("some.permission", false);

            CommandValidator.ValidationResult result = validator.validate(createConsoleContext(null));

            assertFalse(result.isValid(),
                    "if this ever passes, the class-level branch really has become dead and the "
                            + "class should be reconsidered rather than left in place");
        }

        /**
         * The mechanism that makes the same branch unreachable for players, pinned so that
         * removing it is a deliberate act rather than an accident.
         * <p>
         * {@code CommandManager.registerCommandDirect} calls {@code command.setPermission(...)}
         * with {@code @CmdExecutor}'s value, so Paper filters the command out of an unpermitted
         * player's command tree and answers with a parse error before {@code onCommand} runs.
         * Measured across 22 permission-gated commands on a real 1.21.4 server: a deop'd player
         * got {@code Unknown or incomplete command} every time and never this validator's message.
         * <p>
         * Deleting that {@code setPermission} call would make players see the message -- and would
         * also make every permission-gated command visible in every player's tab completion. That
         * is a product decision, not a bug fix. This assertion is here so the decision cannot be
         * made by accident while "fixing" a validator that looks broken.
         */
        @Test
        @DisplayName("CommandManager still registers the class-level permission with Bukkit")
        void bukkitLevelRegistrationStillHappens() throws Exception {
            java.lang.reflect.Method register = com.ultikits.ultitools.manager.CommandManager.class
                    .getDeclaredMethod("registerCommandDirect",
                            org.bukkit.command.CommandExecutor.class, String.class, String.class,
                            String[].class);

            assertNotNull(register,
                    "registerCommandDirect is what sets the Bukkit-level permission; if it has "
                            + "been renamed or removed, re-read PermissionValidator's javadoc "
                            + "before assuming players now reach the class-level check");

            String body = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "src/main/java/com/ultikits/ultitools/manager/CommandManager.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("command.setPermission(permission)"),
                    "the Bukkit-level permission registration is gone -- players now reach "
                            + "PermissionValidator's class-level branch, and its javadoc plus "
                            + "issue #383 no longer describe reality");
        }
    }
}

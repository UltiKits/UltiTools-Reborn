package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.UUID;

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
import com.ultikits.ultitools.annotations.command.CmdCD;

/**
 * Comprehensive unit tests for CooldownValidator.
 * Tests cooldown validation, application, clearing and cleanup functionality.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CooldownValidator Tests")
class CooldownValidatorTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private ConsoleCommandSender mockConsole;

    @Mock
    private Command mockCommand;

    @Mock
    private UltiTools mockUltiTools;

    private MockedStatic<UltiTools> mockedUltiTools;

    private CooldownValidator validator;
    private UUID playerUUID;

    @BeforeEach
    void setUp() {
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        playerUUID = UUID.randomUUID();
        lenient().when(mockPlayer.getUniqueId()).thenReturn(playerUUID);
        lenient().when(mockCommand.getName()).thenReturn("test");

        validator = new CooldownValidator();
    }

    @AfterEach
    void tearDown() {
        if (mockedUltiTools != null) {
            mockedUltiTools.close();
        }
    }

    // Test methods with @CmdCD annotation for testing
    @CmdCD(5)
    public void methodWithCooldown() {}

    @CmdCD(0)
    public void methodWithZeroCooldown() {}

    public void methodWithoutCooldown() {}

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
        @DisplayName("Default constructor creates validator with no default cooldown")
        void defaultConstructorNoDefaultCooldown() {
            CooldownValidator v = new CooldownValidator();
            assertEquals("CooldownValidator", v.getName());
            assertEquals(300, v.getOrder());
        }

        @Test
        @DisplayName("Constructor with default cooldown sets value correctly")
        void constructorWithDefaultCooldown() throws Exception {
            CooldownValidator v = new CooldownValidator(10);
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");
            CommandContext context = createPlayerContext(method);

            // Apply cooldown and verify it's applied with default value
            v.applyCooldown(context);
            
            CommandValidator.ValidationResult result = v.validate(context);
            assertFalse(result.isValid());
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should pass for console sender")
        void shouldPassForConsoleSender() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createConsoleContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should pass when no method is matched")
        void shouldPassWhenNoMethodMatched() {
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
        @DisplayName("Should pass for method without CmdCD annotation")
        void shouldPassWithoutCmdCDAnnotation() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should pass for method with zero cooldown")
        void shouldPassWithZeroCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithZeroCooldown");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should pass when not on cooldown")
        void shouldPassWhenNotOnCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when on cooldown")
        void shouldFailWhenOnCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            // Apply cooldown first
            validator.applyCooldown(context);

            // Now validation should fail
            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertNotNull(result.getErrorMessage());
            assertEquals("command.error.cooldown", result.getErrorKey());
        }

        @Test
        @DisplayName("Error message should contain remaining seconds")
        void errorMessageShouldContainRemainingSeconds() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            // Message format contains %d for remaining seconds
            assertTrue(result.getErrorMessage().contains("操作频繁"));
        }
    }

    @Nested
    @DisplayName("Apply Cooldown Tests")
    class ApplyCooldownTests {

        @Test
        @DisplayName("Should not apply cooldown for console sender")
        void shouldNotApplyForConsoleSender() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createConsoleContext(method);

            validator.applyCooldown(context);

            // Validation should still pass for player since cooldown wasn't applied to any player
            CommandContext playerContext = createPlayerContext(method);
            CommandValidator.ValidationResult result = validator.validate(playerContext);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should not apply cooldown when no method matched")
        void shouldNotApplyWhenNoMethodMatched() {
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            validator.applyCooldown(context);

            // Should not throw and subsequent validation should pass
            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should not apply cooldown for zero cooldown value")
        void shouldNotApplyForZeroCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithZeroCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should apply cooldown correctly for annotated method")
        void shouldApplyCooldownForAnnotatedMethod() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("Cooldowns should be separate per player")
        void cooldownsShouldBeSeparatePerPlayer() throws Exception {
            UUID player2UUID = UUID.randomUUID();
            Player mockPlayer2 = mock(Player.class);
            when(mockPlayer2.getUniqueId()).thenReturn(player2UUID);

            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");

            CommandContext context1 = createPlayerContext(method);
            CommandContext context2 = CommandContext.builder()
                    .sender(mockPlayer2)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(method)
                    .build();

            // Apply cooldown only for player 1
            validator.applyCooldown(context1);

            // Player 1 should be on cooldown
            assertFalse(validator.validate(context1).isValid());

            // Player 2 should NOT be on cooldown
            assertTrue(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Cooldowns should be separate per method")
        void cooldownsShouldBeSeparatePerMethod() throws Exception {
            Method method1 = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            Method method2 = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");

            // Use validator with default cooldown to test method without annotation
            CooldownValidator validatorWithDefault = new CooldownValidator(5);

            CommandContext context1 = createPlayerContext(method1);
            CommandContext context2 = createPlayerContext(method2);

            // Apply cooldown only for method 1
            validatorWithDefault.applyCooldown(context1);

            // Method 1 should be on cooldown
            assertFalse(validatorWithDefault.validate(context1).isValid());

            // Method 2 should NOT be on cooldown (uses default 5s cooldown from constructor)
            assertTrue(validatorWithDefault.validate(context2).isValid());
        }
    }

    @Nested
    @DisplayName("Clear Cooldown Tests")
    class ClearCooldownTests {

        @Test
        @DisplayName("Should clear all cooldowns for a player")
        void shouldClearAllCooldownsForPlayer() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);
            assertFalse(validator.validate(context).isValid());

            // Clear cooldowns
            validator.clearCooldowns(playerUUID);

            // Should pass now
            assertTrue(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("Should clear specific cooldown for a player")
        void shouldClearSpecificCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);
            String methodKey = method.toString();

            validator.applyCooldown(context);
            assertFalse(validator.validate(context).isValid());

            // Clear specific cooldown
            validator.clearCooldown(playerUUID, methodKey);

            // Should pass now
            assertTrue(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("Clearing specific cooldown should not affect other cooldowns")
        void clearingSpecificShouldNotAffectOthers() throws Exception {
            CooldownValidator validatorWithDefault = new CooldownValidator(5);

            Method method1 = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            Method method2 = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");

            CommandContext context1 = createPlayerContext(method1);
            CommandContext context2 = createPlayerContext(method2);

            // Apply cooldown for both methods
            validatorWithDefault.applyCooldown(context1);
            validatorWithDefault.applyCooldown(context2);

            // Clear only method1's cooldown
            validatorWithDefault.clearCooldown(playerUUID, method1.toString());

            // Method 1 should pass, method 2 should still be on cooldown
            assertTrue(validatorWithDefault.validate(context1).isValid());
            assertFalse(validatorWithDefault.validate(context2).isValid());
        }

        @Test
        @DisplayName("Clearing non-existent player cooldowns should not throw")
        void clearingNonExistentPlayerShouldNotThrow() {
            UUID nonExistentUUID = UUID.randomUUID();
            assertDoesNotThrow(() -> validator.clearCooldowns(nonExistentUUID));
        }

        @Test
        @DisplayName("Clearing non-existent method cooldown should not throw")
        void clearingNonExistentMethodShouldNotThrow() {
            assertDoesNotThrow(() -> validator.clearCooldown(playerUUID, "nonexistent.method"));
        }
    }

    @Nested
    @DisplayName("Get Remaining Cooldown Tests")
    class GetRemainingCooldownTests {

        @Test
        @DisplayName("Should return 0 when not on cooldown")
        void shouldReturnZeroWhenNotOnCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            String methodKey = method.toString();

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertEquals(0, remaining);
        }

        @Test
        @DisplayName("Should return remaining seconds when on cooldown")
        void shouldReturnRemainingSecondsWhenOnCooldown() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            // Remaining should be positive and at most cooldown + 1 (due to +1 in method)
            // @CmdCD(5) means 5 seconds, but getRemainingCooldown adds 1 to prevent 0 display
            assertTrue(remaining >= 1 && remaining <= 6, 
                    "Remaining cooldown should be between 1 and 6, but was: " + remaining);
        }

        @Test
        @DisplayName("Should return 0 for non-existent player")
        void shouldReturnZeroForNonExistentPlayer() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            String methodKey = method.toString();

            long remaining = validator.getRemainingCooldown(UUID.randomUUID(), methodKey);
            assertEquals(0, remaining);
        }

        @Test
        @DisplayName("Should return 0 for non-existent method key")
        void shouldReturnZeroForNonExistentMethodKey() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, "nonexistent.method");
            assertEquals(0, remaining);
        }
    }

    @Nested
    @DisplayName("Cleanup Expired Tests")
    class CleanupExpiredTests {

        @Test
        @DisplayName("Should remove expired cooldowns")
        void shouldRemoveExpiredCooldowns() throws Exception {
            // Use a very short cooldown validator for testing
            CooldownValidator shortValidator = new CooldownValidator(0); // 0 means instant expiry

            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");
            CommandContext context = createPlayerContext(method);

            // Manually setting up won't create a cooldown since default is 0
            // This test verifies cleanup doesn't break with empty data
            shortValidator.cleanupExpired();

            // Should not throw
            assertTrue(shortValidator.validate(context).isValid());
        }

        @Test
        @DisplayName("Cleanup should not throw on empty cooldowns")
        void cleanupShouldNotThrowOnEmptyCooldowns() {
            assertDoesNotThrow(() -> validator.cleanupExpired());
        }

        @Test
        @DisplayName("Cleanup should keep active cooldowns")
        void cleanupShouldKeepActiveCooldowns() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            validator.applyCooldown(context);
            validator.cleanupExpired();

            // Cooldown should still be active since it hasn't expired
            assertFalse(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("Cleanup should clean player entries with all expired cooldowns")
        void cleanupShouldCleanEmptyPlayerEntries() throws Exception {
            // Create a validator that we can manipulate
            CooldownValidator testValidator = new CooldownValidator(1); // 1 second cooldown
            
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutCooldown");
            CommandContext context = createPlayerContext(method);
            
            testValidator.applyCooldown(context);
            
            // Wait for cooldown to expire
            Thread.sleep(1100);
            
            // Cleanup should remove expired entries
            testValidator.cleanupExpired();
            
            // Cooldown should be gone
            assertTrue(testValidator.validate(context).isValid());
        }
    }

    @Nested
    @DisplayName("Order and Name Tests")
    class OrderAndNameTests {

        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            assertEquals(300, validator.getOrder());
        }

        @Test
        @DisplayName("Should have correct name")
        void shouldHaveCorrectName() {
            assertEquals("CooldownValidator", validator.getName());
        }
    }
}

package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

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
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * Comprehensive unit tests for UsageLockValidator.
 * Tests lock acquisition, release, and validation for sender-specific and server-wide locks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UsageLockValidator Tests")
class UsageLockValidatorTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private Player mockPlayer2;

    @Mock
    private ConsoleCommandSender mockConsole;

    @Mock
    private Command mockCommand;

    @Mock
    private UltiTools mockUltiTools;

    private MockedStatic<UltiTools> mockedUltiTools;

    private UsageLockValidator validator;
    private UUID player1UUID;
    private UUID player2UUID;

    @BeforeEach
    void setUp() {
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        player1UUID = UUID.randomUUID();
        player2UUID = UUID.randomUUID();
        lenient().when(mockPlayer.getUniqueId()).thenReturn(player1UUID);
        lenient().when(mockPlayer2.getUniqueId()).thenReturn(player2UUID);
        lenient().when(mockCommand.getName()).thenReturn("test");

        validator = new UsageLockValidator();
    }

    @AfterEach
    void tearDown() {
        if (mockedUltiTools != null) {
            mockedUltiTools.close();
        }
    }

    // Test methods with @UsageLimit annotation
    // Test stub methods for reflection testing - intentionally empty
    @UsageLimit(value = UsageLimit.LimitType.SENDER)
    public void senderLimitedMethod() { /* Test stub for annotation testing */ }

    @UsageLimit(value = UsageLimit.LimitType.ALL)
    public void serverWideLimitedMethod() { /* Test stub for annotation testing */ }

    @UsageLimit(value = UsageLimit.LimitType.SENDER, ContainConsole = true)
    public void senderLimitedWithConsole() { /* Test stub for annotation testing */ }

    @UsageLimit(value = UsageLimit.LimitType.ALL, ContainConsole = true)
    public void serverWideLimitedWithConsole() { /* Test stub for annotation testing */ }

    @UsageLimit(value = UsageLimit.LimitType.NONE)
    public void noLimitMethod() { /* Test stub for annotation testing */ }

    public void methodWithoutUsageLimit() { /* Test stub for annotation testing */ }

    private CommandContext createPlayerContext(Method method, Player player) {
        return CommandContext.builder()
                .sender(player)
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
    @DisplayName("Validation Without UsageLimit Tests")
    class NoUsageLimitTests {

        @Test
        @DisplayName("Should pass when method has no UsageLimit annotation")
        void shouldPassWithoutUsageLimitAnnotation() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutUsageLimit");
            CommandContext context = createPlayerContext(method, mockPlayer);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should pass when method is null")
        void shouldPassWhenMethodIsNull() {
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
        @DisplayName("Should pass when limit type is NONE")
        void shouldPassWhenLimitTypeIsNone() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("noLimitMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("SENDER Lock Validation Tests")
    class SenderLockValidationTests {

        @Test
        @DisplayName("Should pass when sender is not locked")
        void shouldPassWhenSenderNotLocked() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when sender is locked")
        void shouldFailWhenSenderIsLocked() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            // Acquire lock
            assertTrue(validator.acquireLock(context));

            // Validation should fail
            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.sender_locked", result.getErrorKey());
        }

        @Test
        @DisplayName("Sender lock should not affect other players")
        void senderLockShouldNotAffectOthers() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context1 = createPlayerContext(method, mockPlayer);
            CommandContext context2 = createPlayerContext(method, mockPlayer2);

            // Lock player 1
            assertTrue(validator.acquireLock(context1));

            // Player 1 should be locked
            assertFalse(validator.validate(context1).isValid());

            // Player 2 should NOT be locked
            assertTrue(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Console should pass SENDER lock validation when ContainConsole is false")
        void consoleShouldPassSenderLockWhenContainConsoleFalse() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should always pass when ContainConsole is false
            CommandValidator.ValidationResult result = validator.validate(consoleContext);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("ALL Lock Validation Tests")
    class AllLockValidationTests {

        @Test
        @DisplayName("Should pass when server is not locked")
        void shouldPassWhenServerNotLocked() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should fail when server is locked by any player")
        void shouldFailWhenServerIsLocked() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context1 = createPlayerContext(method, mockPlayer);
            CommandContext context2 = createPlayerContext(method, mockPlayer2);

            // Lock server via player 1
            assertTrue(validator.acquireLock(context1));

            // Both players should be blocked
            assertFalse(validator.validate(context1).isValid());
            assertFalse(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Server lock error message should indicate waiting for others")
        void serverLockErrorShouldIndicateWaitingForOthers() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            validator.acquireLock(context);

            CommandValidator.ValidationResult result = validator.validate(context);
            assertFalse(result.isValid());
            assertEquals("command.error.server_locked", result.getErrorKey());
        }

        @Test
        @DisplayName("Console should pass ALL lock validation when ContainConsole is false")
        void consoleShouldPassAllLockWhenContainConsoleFalse() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should pass when ContainConsole is false
            CommandValidator.ValidationResult result = validator.validate(consoleContext);
            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Lock Acquisition Tests")
    class LockAcquisitionTests {

        @Test
        @DisplayName("Should return true when acquiring lock for first time")
        void shouldReturnTrueForFirstAcquisition() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            assertTrue(validator.acquireLock(context));
        }

        @Test
        @DisplayName("Should return false when acquiring lock for same sender/method again")
        void shouldReturnFalseForDuplicateAcquisition() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            assertTrue(validator.acquireLock(context));
            assertFalse(validator.acquireLock(context));
        }

        @Test
        @DisplayName("Should return true when method has no UsageLimit")
        void shouldReturnTrueWhenNoUsageLimit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithoutUsageLimit");
            CommandContext context = createPlayerContext(method, mockPlayer);

            assertTrue(validator.acquireLock(context));
        }

        @Test
        @DisplayName("Should return true when method is null")
        void shouldReturnTrueWhenMethodIsNull() {
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            assertTrue(validator.acquireLock(context));
        }

        @Test
        @DisplayName("Server-wide lock should prevent other players from acquiring")
        void serverLockShouldPreventOthersFromAcquiring() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context1 = createPlayerContext(method, mockPlayer);
            CommandContext context2 = createPlayerContext(method, mockPlayer2);

            // Player 1 acquires lock
            assertTrue(validator.acquireLock(context1));

            // Player 2 cannot acquire lock
            assertFalse(validator.acquireLock(context2));
        }

        @Test
        @DisplayName("Console should return true for lock acquisition when ContainConsole is false")
        void consoleShouldReturnTrueWhenContainConsoleFalse() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.acquireLock(consoleContext));
        }
    }

    @Nested
    @DisplayName("Lock Release Tests")
    class LockReleaseTests {

        @Test
        @DisplayName("Should release sender lock correctly")
        void shouldReleaseSenderLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            validator.acquireLock(context);
            assertFalse(validator.validate(context).isValid());

            validator.releaseLock(context);
            assertTrue(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("Should release server-wide lock correctly")
        void shouldReleaseServerWideLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context1 = createPlayerContext(method, mockPlayer);
            CommandContext context2 = createPlayerContext(method, mockPlayer2);

            validator.acquireLock(context1);
            assertFalse(validator.validate(context2).isValid());

            validator.releaseLock(context1);
            assertTrue(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Releasing non-existent lock should not throw")
        void releasingNonExistentLockShouldNotThrow() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            assertDoesNotThrow(() -> validator.releaseLock(context));
        }

        @Test
        @DisplayName("Release lock with null method should not throw")
        void releaseLockWithNullMethodShouldNotThrow() {
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            assertDoesNotThrow(() -> validator.releaseLock(context));
        }
    }

    @Nested
    @DisplayName("Clear Player Locks Tests")
    class ClearPlayerLocksTests {

        @Test
        @DisplayName("Should clear all sender locks for a player")
        void shouldClearAllSenderLocksForPlayer() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            validator.acquireLock(context);
            assertFalse(validator.validate(context).isValid());

            validator.clearPlayerLocks(player1UUID);
            assertTrue(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("Should clear server-wide locks held by player")
        void shouldClearServerLocksHeldByPlayer() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context1 = createPlayerContext(method, mockPlayer);
            CommandContext context2 = createPlayerContext(method, mockPlayer2);

            validator.acquireLock(context1);
            assertFalse(validator.validate(context2).isValid());

            validator.clearPlayerLocks(player1UUID);
            assertTrue(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Clearing non-existent player locks should not throw")
        void clearingNonExistentPlayerLocksShouldNotThrow() {
            assertDoesNotThrow(() -> validator.clearPlayerLocks(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("Clear All Locks Tests")
    class ClearAllLocksTests {

        @Test
        @DisplayName("Should clear all locks")
        void shouldClearAllLocks() throws Exception {
            Method senderMethod = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            Method serverMethod = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext senderContext = createPlayerContext(senderMethod, mockPlayer);
            CommandContext serverContext = createPlayerContext(serverMethod, mockPlayer2);

            validator.acquireLock(senderContext);
            validator.acquireLock(serverContext);

            assertFalse(validator.validate(senderContext).isValid());
            assertFalse(validator.validate(serverContext).isValid());

            validator.clearAllLocks();

            assertTrue(validator.validate(senderContext).isValid());
            assertTrue(validator.validate(serverContext).isValid());
        }

        @Test
        @DisplayName("Clearing all locks on empty should not throw")
        void clearingAllLocksOnEmptyShouldNotThrow() {
            assertDoesNotThrow(() -> validator.clearAllLocks());
        }
    }

    @Nested
    @DisplayName("IsLocked Method Tests")
    class IsLockedTests {

        @Test
        @DisplayName("Should return false when not locked")
        void shouldReturnFalseWhenNotLocked() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            String methodKey = method.toString();

            assertFalse(validator.isLocked(player1UUID, methodKey));
        }

        @Test
        @DisplayName("Should return true for sender lock")
        void shouldReturnTrueForSenderLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);
            String methodKey = method.toString();

            validator.acquireLock(context);
            assertTrue(validator.isLocked(player1UUID, methodKey));
        }

        @Test
        @DisplayName("Should return true for server-wide lock")
        void shouldReturnTrueForServerWideLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);
            String methodKey = method.toString();

            validator.acquireLock(context);
            
            // Both players should see it as locked
            assertTrue(validator.isLocked(player1UUID, methodKey));
            assertTrue(validator.isLocked(player2UUID, methodKey));
        }

        @Test
        @DisplayName("Should return false for non-existent method key")
        void shouldReturnFalseForNonExistentMethodKey() {
            assertFalse(validator.isLocked(player1UUID, "nonexistent.method"));
        }
    }

    @Nested
    @DisplayName("ContainConsole Tests")
    class ContainConsoleTests {

        @Test
        @DisplayName("SENDER lock with ContainConsole=true should validate console")
        void senderLockWithContainConsoleShouldValidateConsole() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedWithConsole");
            CommandContext consoleContext = createConsoleContext(method);

            // Console validation should pass initially (no lock acquired)
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("ALL lock with ContainConsole=true should validate console")
        void allLockWithContainConsoleShouldValidateConsole() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedWithConsole");
            CommandContext playerContext = createPlayerContext(method, mockPlayer);
            CommandContext consoleContext = createConsoleContext(method);

            // Player acquires lock
            validator.acquireLock(playerContext);

            // Console should be blocked when ContainConsole=true
            assertFalse(validator.validate(consoleContext).isValid());
        }
    }

    @Nested
    @DisplayName("Order and Name Tests")
    class OrderAndNameTests {

        @Test
        @DisplayName("Should have correct order priority")
        void shouldHaveCorrectOrder() {
            assertEquals(250, validator.getOrder());
        }

        @Test
        @DisplayName("Should have correct name")
        void shouldHaveCorrectName() {
            assertEquals("UsageLockValidator", validator.getName());
        }
    }

    @Nested
    @DisplayName("Multiple Methods Lock Tests")
    class MultipleMethodsLockTests {

        @Test
        @DisplayName("Locks should be independent per method")
        void locksShouldBeIndependentPerMethod() throws Exception {
            Method method1 = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            Method method2 = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context1 = createPlayerContext(method1, mockPlayer);
            CommandContext context2 = createPlayerContext(method2, mockPlayer);

            validator.acquireLock(context1);

            // Method 1 should be locked, method 2 should not
            assertFalse(validator.validate(context1).isValid());
            assertTrue(validator.validate(context2).isValid());
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle rapid lock/unlock cycles")
        void shouldHandleRapidLockUnlockCycles() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            for (int i = 0; i < 100; i++) {
                assertTrue(validator.acquireLock(context));
                assertFalse(validator.validate(context).isValid());
                validator.releaseLock(context);
                assertTrue(validator.validate(context).isValid());
            }
        }

        @Test
        @DisplayName("Should handle multiple players with multiple methods")
        void shouldHandleMultiplePlayersWithMultipleMethods() throws Exception {
            Method senderMethod = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            Method serverMethod = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");

            CommandContext p1sender = createPlayerContext(senderMethod, mockPlayer);
            CommandContext p2sender = createPlayerContext(senderMethod, mockPlayer2);
            CommandContext p1server = createPlayerContext(serverMethod, mockPlayer);

            // Player 1 locks sender method
            validator.acquireLock(p1sender);

            // Player 2 can still use sender method (separate lock)
            assertTrue(validator.validate(p2sender).isValid());

            // Player 1 locks server method
            validator.acquireLock(p1server);

            // Now player 2 is blocked from server method
            CommandContext p2server = createPlayerContext(serverMethod, mockPlayer2);
            assertFalse(validator.validate(p2server).isValid());

            // But player 2's sender method is still available
            assertTrue(validator.validate(p2sender).isValid());
        }

        @Test
        @DisplayName("Console should not acquire sender lock when ContainConsole is false")
        void consoleShouldNotAcquireSenderLockWhenContainConsoleFalse() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should return true (no lock acquired, treated as success)
            assertTrue(validator.acquireLock(consoleContext));
            
            // Validation should also pass
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Console should not acquire server lock when ContainConsole is false")
        void consoleShouldNotAcquireServerLockWhenContainConsoleFalse() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should return true (no lock acquired)
            assertTrue(validator.acquireLock(consoleContext));
            
            // Player should still be able to acquire lock
            CommandContext playerContext = createPlayerContext(method, mockPlayer);
            assertTrue(validator.acquireLock(playerContext));
        }

        @Test
        @DisplayName("Release lock with NONE limit type should not throw")
        void releaseLockWithNoneLimitTypeShouldNotThrow() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("noLimitMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            assertDoesNotThrow(() -> validator.releaseLock(context));
        }

        @Test
        @DisplayName("Clear player locks should handle player with only server locks")
        void clearPlayerLocksShouldHandleServerLocksOnly() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            validator.acquireLock(context);
            
            // Verify lock is active
            CommandContext context2 = createPlayerContext(method, mockPlayer2);
            assertFalse(validator.validate(context2).isValid());

            // Clear player 1's locks
            validator.clearPlayerLocks(player1UUID);

            // Player 2 should now be able to proceed
            assertTrue(validator.validate(context2).isValid());
        }

        @Test
        @DisplayName("Release should work for console with SENDER limit and ContainConsole false")
        void releaseShouldWorkForConsoleWithSenderLimit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Should not throw even though console doesn't have locks
            assertDoesNotThrow(() -> validator.releaseLock(consoleContext));
        }

        @Test
        @DisplayName("Release should work for console with ALL limit")
        void releaseShouldWorkForConsoleWithAllLimit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Should not throw
            assertDoesNotThrow(() -> validator.releaseLock(consoleContext));
        }
    }
}

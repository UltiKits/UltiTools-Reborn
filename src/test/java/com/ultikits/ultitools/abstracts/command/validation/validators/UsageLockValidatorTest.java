package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
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
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;

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
    private Player mockPlayer3;

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
    private UUID player3UUID;

    @BeforeEach
    void setUp() {
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        player1UUID = UUID.randomUUID();
        player2UUID = UUID.randomUUID();
        player3UUID = UUID.randomUUID();
        lenient().when(mockPlayer.getUniqueId()).thenReturn(player1UUID);
        lenient().when(mockPlayer2.getUniqueId()).thenReturn(player2UUID);
        lenient().when(mockPlayer3.getUniqueId()).thenReturn(player3UUID);
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

    @UsageLimit(value = UsageLimit.LimitType.ALL, ContainConsole = false)
    public void serverWideLimitedNoConsole() { /* Test stub for annotation testing */ }

    public void methodWithoutUsageLimit() { /* Test stub for annotation testing */ }

    /**
     * Distinct class carrying a method with the SAME simple name as
     * {@link #serverWideLimitedMethod()}, for the GEN-09 key-equality edge (Method#toString()
     * embeds the fully-qualified declaring class, so identical simple names never collide).
     */
    static class OtherFixtureWithSameMethodName {
        @UsageLimit(value = UsageLimit.LimitType.ALL)
        public void serverWideLimitedMethod() { /* Test stub - same simple name, different declaring class */ }
    }

    /**
     * Fixtures for the class-level @UsageLimit fallback (D-01 follow-up, most-derived-wins) --
     * see {@link com.ultikits.ultitools.utils.ReflectionUtil#resolveMethodOrClassAnnotation}.
     */
    static class MethodLevelOnlyUsageLimitFixture {
        @UsageLimit(value = UsageLimit.LimitType.SENDER)
        public void methodWithOwnLimit() { /* Test stub */ }
    }

    @UsageLimit(value = UsageLimit.LimitType.ALL)
    static class ClassLevelUsageLimitFixture {
        public void methodWithoutOwnLimit() { /* Test stub */ }

        @UsageLimit(value = UsageLimit.LimitType.SENDER)
        public void methodWithOwnLimitOverride() { /* Test stub */ }
    }

    /**
     * WR-02 (05-REVIEW.md) fixtures: a concrete executor SUBCLASS inheriting an unoverridden
     * {@code @CmdMapping}-shaped method from a superclass -- {@code sharedMethod()}'s declaring
     * class is always {@code SharedUsageLimitMappingBase}, regardless of which concrete
     * subclass below is used to build the context. Distinguishes ALL-scope from SENDER-scope
     * behaviourally (blocks a DIFFERENT sender under ALL but not under SENDER), matching this
     * file's existing {@code ClassLevelFallbackTests} convention.
     */
    static class SharedUsageLimitMappingBase {
        public void sharedMethod() { /* Test stub */ }
    }

    // Combo 1 (the WR-02 broken case): @UsageLimit ONLY on the concrete subclass.
    @UsageLimit(value = UsageLimit.LimitType.ALL)
    static class ConcreteSubclassOnlyUsageLimitFixture extends SharedUsageLimitMappingBase {
        // inherits sharedMethod(); only this subclass carries @UsageLimit
    }

    // Combo 2: @UsageLimit ONLY on the declaring superclass -- pre-WR-02 behaviour, unchanged.
    @UsageLimit(value = UsageLimit.LimitType.ALL)
    static class DeclaringSuperclassUsageLimitBase {
        public void sharedMethod() { /* Test stub */ }
    }

    static class ConcreteSubclassNoOwnUsageLimitFixture extends DeclaringSuperclassUsageLimitBase {
        // inherits sharedMethod() and the superclass's @UsageLimit; declares none of its own
    }

    // Combo 3: BOTH levels carry @UsageLimit with DIFFERENT scopes -- the concrete subclass
    // (ALL) must win over the superclass (SENDER).
    @UsageLimit(value = UsageLimit.LimitType.SENDER)
    static class BothLevelsSuperclassUsageLimitBase {
        public void sharedMethod() { /* Test stub */ }
    }

    @UsageLimit(value = UsageLimit.LimitType.ALL)
    static class BothLevelsConcreteSubclassUsageLimitFixture extends BothLevelsSuperclassUsageLimitBase {
        // inherits sharedMethod(); both this class and its superclass carry @UsageLimit
    }

    private CommandContext createPlayerContext(Method method, Player player) {
        return CommandContext.builder()
                .sender(player)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .matchedMethod(method)
                .build();
    }

    /**
     * WR-02 overload: also threads the concrete executor class into the context, so
     * {@code UsageLockValidator} can resolve a class-level {@code @UsageLimit} against it (not
     * just {@code method.getDeclaringClass()}).
     */
    private CommandContext createPlayerContext(Method method, Player player, Class<?> executorClass) {
        return CommandContext.builder()
                .sender(player)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .matchedMethod(method)
                .executorClass(executorClass)
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
        @DisplayName("Should handle rapid lock/unlock cycles via validate()+onComplete() (acquire-as-you-validate, D-02)")
        void shouldHandleRapidLockUnlockCycles() throws Exception {
            // D-02 adjudication: this test previously called acquireLock() then validate() then
            // releaseLock() then validate() again in the same iteration, assuming validate() is a
            // pure read. Under acquire-as-you-validate (Task 2's chosen ordering, recorded in
            // UsageLockValidator's class javadoc), validate() itself performs the acquisition --
            // so a bare validate() call is no longer idempotent, it is the acquisition step. The
            // scenario (rapid lock/unlock symmetry) is preserved; the call pattern is corrected to
            // match the new, plan-mandated contract instead of the old read-only one.
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            for (int i = 0; i < 100; i++) {
                // validate() IS the acquisition step: the first call in a fresh cycle acquires
                // the lock as a side effect and succeeds.
                assertTrue(validator.validate(context).isValid());
                // A second validate() call before release finds the lock already held.
                assertFalse(validator.validate(context).isValid());
                // onComplete() is the release step -- it must free exactly what validate()
                // acquired, restoring the cycle to its starting state.
                validator.onComplete(context, true);
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

            // Player 2 can still use sender method (separate lock). validate() acquires it as a
            // side effect under acquire-as-you-validate (D-02), so release it immediately
            // afterward (as a real dispatch's onComplete would) to keep this check independent
            // of the final re-check below.
            assertTrue(validator.validate(p2sender).isValid());
            validator.onComplete(p2sender, true);

            // Player 1 locks server method
            validator.acquireLock(p1server);

            // Now player 2 is blocked from server method
            CommandContext p2server = createPlayerContext(serverMethod, mockPlayer2);
            assertFalse(validator.validate(p2server).isValid());

            // But player 2's sender method is still available -- independent of player 1's
            // unrelated server-method lock, not merely a re-acquisition of what it already holds.
            assertTrue(validator.validate(p2sender).isValid());
        }

        @Test
        @DisplayName("Console never OWNS a SENDER-scope lock, regardless of ContainConsole (senderLocks is keyed by player UUID)")
        void consoleNeverOwnsSenderLockRegardlessOfContainConsole() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should return true (no lock acquired, treated as success) -- true whether
            // ContainConsole() is true (its 6.3.0 default, used here) or false, since senderLocks
            // has no representation for a non-player sender at all.
            assertTrue(validator.acquireLock(consoleContext));
            
            // Validation should also pass
            assertTrue(validator.validate(consoleContext).isValid());
        }

        @Test
        @DisplayName("Console never OWNS an ALL-scope lock, regardless of ContainConsole (serverLocks' value is a player UUID)")
        void consoleNeverOwnsServerLockRegardlessOfContainConsole() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext consoleContext = createConsoleContext(method);

            // Console should return true (no lock acquired) when nothing else holds it -- true
            // whether ContainConsole() is true (its 6.3.0 default, used here) or false, since a
            // console sender can never be recorded as an ALL-scope lock's owner.
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

    @Nested
    @DisplayName("GEN-09 Serialization Guarantee Tests (D-02)")
    class SerializationGuaranteeTests {

        @Test
        @DisplayName("GEN-09 Test 1: two senders contending for an ALL-scope lock -- second acquisition fails")
        void secondSenderAcquisitionFailsUnderContention() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext contextA = createPlayerContext(method, mockPlayer);
            CommandContext contextB = createPlayerContext(method, mockPlayer2);

            assertTrue(validator.validate(contextA).isValid(), "A's acquisition (via validate()) must succeed");
            assertFalse(validator.validate(contextB).isValid(), "B's acquisition must fail while A holds the lock");
        }

        @Test
        @DisplayName("GEN-09 Test 2 (Pitfall 5): B's post-action does not free A's ALL-scope lock")
        void bPostActionDoesNotFreeAsLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext contextA = createPlayerContext(method, mockPlayer);
            CommandContext contextB = createPlayerContext(method, mockPlayer2);
            CommandContext contextC = createPlayerContext(method, mockPlayer3);

            assertTrue(validator.validate(contextA).isValid(), "A acquires the lock");
            assertFalse(validator.validate(contextB).isValid(), "B is rejected while A holds the lock");

            // B never held the lock (its acquisition failed), so B's post-action must not free
            // A's. On the pre-6.3.0 build, releaseLock's ALL branch removed unconditionally --
            // this call would have freed A's lock despite B never holding it.
            validator.onComplete(contextB, true);

            assertFalse(validator.validate(contextC).isValid(),
                    "a third party must still be rejected -- A's lock must remain held after B's onComplete");
        }

        @Test
        @DisplayName("GEN-09 Test 3: A's own post-action frees A's ALL-scope lock")
        void aOwnPostActionFreesLock() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext contextA = createPlayerContext(method, mockPlayer);
            CommandContext contextB = createPlayerContext(method, mockPlayer2);

            assertTrue(validator.validate(contextA).isValid());
            validator.onComplete(contextA, true);

            assertTrue(validator.validate(contextB).isValid(), "the lock must be free after A's own post-action");
        }

        @Test
        @DisplayName("GEN-09 Test 4: SENDER scope remains independent per sender (no ownership regression)")
        void senderScopeStaysIndependentPerSender() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext contextA = createPlayerContext(method, mockPlayer);
            CommandContext contextB = createPlayerContext(method, mockPlayer2);

            assertTrue(validator.validate(contextA).isValid(), "A acquires its own SENDER-scope lock");
            assertTrue(validator.validate(contextB).isValid(), "B independently acquires its own SENDER-scope lock");
        }

        @Test
        @DisplayName("GEN-09 Test 5: ContainConsole defaults to true as of 6.3.0 -- console is subject to the limit")
        void containConsoleDefaultsToTrue() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            CommandContext playerContext = createPlayerContext(method, mockPlayer);
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(playerContext).isValid(), "player acquires the ALL-scope lock");
            assertFalse(validator.validate(consoleContext).isValid(),
                    "console must now be gated by the lock -- ContainConsole() defaults to true as of 6.3.0");
        }

        @Test
        @DisplayName("GEN-09 Test 6a: a null matched method acquires nothing and blocks nothing")
        void nullMatchedMethodAcquiresNothing() {
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(null)
                    .build();

            assertTrue(validator.validate(context).isValid());
        }

        @Test
        @DisplayName("GEN-09 Test 6b: a non-player sender under explicit ContainConsole=false acquires nothing and blocks nothing")
        void explicitContainConsoleFalseExemptsNonPlayer() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedNoConsole");
            CommandContext consoleContext = createConsoleContext(method);

            assertTrue(validator.validate(consoleContext).isValid(), "console must be exempt when ContainConsole() is explicitly false");

            // And does not block a player from acquiring afterward -- the console never acquired
            // anything to be a false owner of.
            CommandContext playerContext = createPlayerContext(method, mockPlayer);
            assertTrue(validator.validate(playerContext).isValid());
        }

        @Test
        @DisplayName("GEN-09 Test 7: same-named methods on different declaring classes do not share a lock")
        void sameNamedMethodsOnDifferentClassesDoNotShareLock() throws Exception {
            Method methodA = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            Method methodB = OtherFixtureWithSameMethodName.class.getDeclaredMethod("serverWideLimitedMethod");
            assertNotEquals(methodA.toString(), methodB.toString(),
                    "Method#toString() must embed the declaring class so identical simple names do not collide");

            CommandContext contextA = createPlayerContext(methodA, mockPlayer);
            CommandContext contextB = createPlayerContext(methodB, mockPlayer2);

            assertTrue(validator.validate(contextA).isValid());
            assertTrue(validator.validate(contextB).isValid(),
                    "different declaring class -> independent lock key, even though the simple name is identical");
        }
    }

    /**
     * D-01 follow-up (maintainer-required): a class-level {@code @UsageLimit} must actually be
     * honoured by {@code UsageLockValidator} itself, not just accepted by
     * {@code PluginManager}'s load-time structural check -- otherwise the check's own "pass" is
     * a false assurance. Most-derived-wins: a method-level {@code @UsageLimit} takes precedence
     * over a class-level one on the same executor.
     * <p>
     * Each test distinguishes ALL-scope (class-level fixture default) from SENDER-scope
     * (method-level fixture default) behaviourally -- a second, DIFFERENT sender is blocked
     * under ALL but not under SENDER -- rather than merely asserting presence, so "class level
     * is read" and "precedence is backwards" cannot be confused with each other.
     */
    @Nested
    @DisplayName("Class-level @UsageLimit fallback (D-01, most-derived-wins)")
    class ClassLevelFallbackTests {

        @Test
        @DisplayName("Method-level only: SENDER scope applies, unchanged -- blocks the same sender, not a different one")
        void methodLevelOnly_unchanged() throws Exception {
            Method method = MethodLevelOnlyUsageLimitFixture.class.getDeclaredMethod("methodWithOwnLimit");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer)).isValid(),
                    "first acquisition by player1 must succeed");
            assertFalse(validator.validate(createPlayerContext(method, mockPlayer)).isValid(),
                    "SENDER scope must block a second acquisition by the SAME sender");
            assertTrue(validator.validate(createPlayerContext(method, mockPlayer2)).isValid(),
                    "SENDER scope must NOT block a DIFFERENT sender");
        }

        @Test
        @DisplayName("Class-level only: ALL scope applies (new behaviour -- fails on pre-follow-up HEAD)")
        void classLevelOnly_newBehaviorAppliesClassScope() throws Exception {
            Method method = ClassLevelUsageLimitFixture.class.getDeclaredMethod("methodWithoutOwnLimit");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer)).isValid(),
                    "first acquisition by player1 must succeed");
            assertFalse(validator.validate(createPlayerContext(method, mockPlayer2)).isValid(),
                    "class-level ALL scope must block a DIFFERENT sender too -- on pre-follow-up HEAD "
                            + "the method carries no @UsageLimit of its own, so acquireLock() returns true "
                            + "unconditionally and this assertion fails");
        }

        @Test
        @DisplayName("Both present: the method-level SENDER scope wins over the class-level ALL scope")
        void bothPresent_methodLevelWins() throws Exception {
            Method method = ClassLevelUsageLimitFixture.class.getDeclaredMethod("methodWithOwnLimitOverride");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer)).isValid(),
                    "first acquisition by player1 must succeed");
            assertTrue(validator.validate(createPlayerContext(method, mockPlayer2)).isValid(),
                    "method-level SENDER must win: a DIFFERENT sender is NOT blocked, even though the "
                            + "class declares ALL -- if class-level had incorrectly won, this would fail");
            assertFalse(validator.validate(createPlayerContext(method, mockPlayer)).isValid(),
                    "method-level SENDER still blocks the SAME sender's second acquisition");
        }
    }

    /**
     * WR-02 (05-REVIEW.md): {@code ClassLevelFallbackTests} above never exercises a class-level
     * {@code @UsageLimit} declared on a SUBCLASS whose {@code @CmdMapping} method is inherited
     * (unoverridden) from a superclass -- every fixture there declares its class-level
     * annotation on the SAME class that declares the mapping method. This is exactly the WR-02
     * defect: {@code method.getDeclaringClass()}-only resolution never sees a class-level
     * annotation declared on the concrete SUBCLASS. All four combinations from the review's
     * proof-form rule.
     */
    @Nested
    @DisplayName("WR-02: executor-class-aware @UsageLimit fallback (post-review gap closure)")
    class ExecutorClassAwareFallbackTests {

        @Test
        @DisplayName("Concrete subclass only: ALL scope applies to the inherited mapping (WR-02 broken case)")
        void concreteSubclassOnly_appliesSubclassScope() throws Exception {
            Method method = ConcreteSubclassOnlyUsageLimitFixture.class.getMethod("sharedMethod");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer, ConcreteSubclassOnlyUsageLimitFixture.class))
                            .isValid(),
                    "first acquisition by player1 must succeed");
            assertFalse(validator.validate(createPlayerContext(method, mockPlayer2, ConcreteSubclassOnlyUsageLimitFixture.class))
                            .isValid(),
                    "the concrete subclass's ALL scope must block a DIFFERENT sender too");
        }

        @Test
        @DisplayName("Declaring superclass only: ALL scope still applies (regression pin, unchanged)")
        void declaringSuperclassOnly_stillAppliesSuperclassScope() throws Exception {
            Method method = ConcreteSubclassNoOwnUsageLimitFixture.class.getMethod("sharedMethod");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer, ConcreteSubclassNoOwnUsageLimitFixture.class))
                            .isValid(),
                    "first acquisition by player1 must succeed");
            assertFalse(validator.validate(createPlayerContext(method, mockPlayer2, ConcreteSubclassNoOwnUsageLimitFixture.class))
                            .isValid(),
                    "the declaring superclass's ALL scope must still block a DIFFERENT sender");
        }

        @Test
        @DisplayName("Both levels present: the concrete subclass's ALL scope wins over the superclass's SENDER scope")
        void bothLevelsPresent_concreteSubclassWins() throws Exception {
            Method method = BothLevelsConcreteSubclassUsageLimitFixture.class.getMethod("sharedMethod");

            assertTrue(validator.validate(
                            createPlayerContext(method, mockPlayer, BothLevelsConcreteSubclassUsageLimitFixture.class))
                            .isValid(),
                    "first acquisition by player1 must succeed");
            assertFalse(validator.validate(
                            createPlayerContext(method, mockPlayer2, BothLevelsConcreteSubclassUsageLimitFixture.class))
                            .isValid(),
                    "the concrete subclass's ALL scope must win: a DIFFERENT sender IS blocked -- if the "
                            + "superclass's SENDER scope had incorrectly won, this would pass");
        }

        @Test
        @DisplayName("Neither level present: no @UsageLimit anywhere -- validation always succeeds")
        void neitherLevelPresent_alwaysValid() throws Exception {
            Method method = SharedUsageLimitMappingBase.class.getMethod("sharedMethod");

            assertTrue(validator.validate(createPlayerContext(method, mockPlayer, SharedUsageLimitMappingBase.class))
                    .isValid());
            assertTrue(validator.validate(createPlayerContext(method, mockPlayer2, SharedUsageLimitMappingBase.class))
                    .isValid());
        }

        @Test
        @DisplayName("Null executorClass (context built without it): falls back to the pre-WR-02 "
                + "declaring-class-only behaviour")
        void nullExecutorClass_fallsBackToDeclaringClassOnly() throws Exception {
            Method method = ConcreteSubclassOnlyUsageLimitFixture.class.getMethod("sharedMethod");

            // Without executorClass, resolution can only see the declaring superclass
            // (SharedUsageLimitMappingBase), which carries no @UsageLimit of its own -- so
            // neither sender is ever blocked.
            assertTrue(validator.validate(createPlayerContext(method, mockPlayer)).isValid());
            assertTrue(validator.validate(createPlayerContext(method, mockPlayer2)).isValid());
        }
    }

    /**
     * GEN-08 / D-03: {@link #senderLocks}/{@link #serverLocks} are now registered with the live
     * {@link PlayerCacheManager} (lazy first-use, triggered from {@link
     * UsageLockValidator#validate}), so a quitting player's lock entries are pruned through the
     * REAL quit path -- {@link PlayerCacheManager#onPlayerQuit(UUID)}, the same method {@code
     * PluginManager}'s {@code PlayerQuitEvent} listener calls -- rather than by calling {@link
     * UsageLockValidator#clearPlayerLocks(UUID)} directly. These assertions fail on the
     * pre-migration build: nothing was ever registered, so {@code onPlayerQuit} had no tracked
     * field to sweep.
     */
    @Nested
    @DisplayName("PlayerCacheManager quit-based sweep (GEN-08, D-03)")
    class PlayerCacheSweepTests {

        private PlayerCacheManager liveManager;

        @BeforeEach
        void wireLiveManager() {
            liveManager = new PlayerCacheManager();
            PluginManager mockPluginManager = mock(PluginManager.class);
            lenient().when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            lenient().when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);
        }

        @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
        private Map<UUID, Set<String>> senderLocksField() throws Exception {
            Field field = UsageLockValidator.class.getDeclaredField("senderLocks");
            field.setAccessible(true);
            return (Map<UUID, Set<String>>) field.get(validator);
        }

        @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
        private Map<String, UUID> serverLocksField() throws Exception {
            Field field = UsageLockValidator.class.getDeclaredField("serverLocks");
            field.setAccessible(true);
            return (Map<String, UUID>) field.get(validator);
        }

        @Test
        @DisplayName("A SENDER-scope lock entry is gone after its holder quits, observed through the real quit path")
        void senderScopeLockGoneAfterRealQuitPath() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            CommandContext context = createPlayerContext(method, mockPlayer);

            // validate() both acquires the lock (acquire-then-execute, D-02) and triggers lazy
            // first-use registration with the live manager wired above.
            assertTrue(validator.validate(context).isValid());
            assertTrue(senderLocksField().containsKey(player1UUID));

            liveManager.onPlayerQuit(player1UUID);

            assertFalse(senderLocksField().containsKey(player1UUID),
                    "the quitting player's own sender-lock entry must be removed");
        }

        @Test
        @DisplayName("An ALL-scope lock held by the quitting player is gone; one held by another player survives")
        void allScopeLockGoneOnlyForQuittingHolder() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("serverWideLimitedMethod");
            String otherMethodKey = method.toString() + "#other";

            CommandContext context = createPlayerContext(method, mockPlayer);
            assertTrue(validator.validate(context).isValid());
            assertTrue(serverLocksField().containsKey(method.toString()));

            // A second, distinct ALL-scope entry held by a DIFFERENT player -- inserted directly
            // since acquiring the SAME method key twice would be blocked by design.
            serverLocksField().put(otherMethodKey, player2UUID);

            liveManager.onPlayerQuit(player1UUID);

            assertFalse(serverLocksField().containsKey(method.toString()),
                    "the ALL-scope entry held by the quitting player must be removed");
            assertEquals(player2UUID, serverLocksField().get(otherMethodKey),
                    "an ALL-scope entry held by a DIFFERENT, still-online player must be untouched");
        }
    }

    /**
     * GEN-08's own acceptance criterion (D-05): "assert both maps' size returns to 0 after 100
     * players quit". N = 100 is the stated floor. Each test distinguishes a correct per-player
     * sweep from a blunt clear by keeping a subset of senders online.
     */
    @Nested
    @DisplayName("GEN-08 soak: state returns to bounded after N distinct senders quit (D-05)")
    class Gen08SoakTests {

        private static final int SOAK_N = 100;

        private PlayerCacheManager liveManager;

        @BeforeEach
        void wireLiveManager() {
            liveManager = new PlayerCacheManager();
            PluginManager mockPluginManager = mock(PluginManager.class);
            lenient().when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
            lenient().when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);
        }

        @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
        private Map<UUID, Set<String>> senderLocksField() throws Exception {
            Field field = UsageLockValidator.class.getDeclaredField("senderLocks");
            field.setAccessible(true);
            return (Map<UUID, Set<String>>) field.get(validator);
        }

        @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
        private Map<String, UUID> serverLocksField() throws Exception {
            Field field = UsageLockValidator.class.getDeclaredField("serverLocks");
            field.setAccessible(true);
            return (Map<String, UUID>) field.get(validator);
        }

        @Test
        @DisplayName("After " + SOAK_N + " distinct senders acquire and quit, sender-scoped lock state for all of them is empty")
        void senderScopeStateEmptyAfterNDistinctSendersQuit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            UUID[] senders = soakAcquireSenderLocks(method, SOAK_N);

            for (UUID senderUuid : senders) {
                liveManager.onPlayerQuit(senderUuid);
            }

            for (UUID senderUuid : senders) {
                assertFalse(senderLocksField().containsKey(senderUuid));
            }
        }

        @Test
        @DisplayName("With a subset of the " + SOAK_N + " sender-scope holders still online, exactly the "
                + "offline holders' locks are gone and the online holders' locks remain")
        void senderScopeDistinguishesCorrectSweepFromBluntClear() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            UUID[] senders = soakAcquireSenderLocks(method, SOAK_N);

            // Odd-indexed senders quit; even-indexed senders remain online.
            for (int i = 1; i < SOAK_N; i += 2) {
                liveManager.onPlayerQuit(senders[i]);
            }

            for (int i = 0; i < SOAK_N; i++) {
                if (i % 2 == 1) {
                    assertFalse(senderLocksField().containsKey(senders[i]),
                            "offline holder #" + i + " must have its sender-scope lock removed");
                } else {
                    assertTrue(senderLocksField().containsKey(senders[i]),
                            "still-online holder #" + i + " must retain its sender-scope lock -- a "
                                    + "blunt clear would wrongly wipe this too");
                }
            }
        }

        @Test
        @DisplayName("After " + SOAK_N + " distinct ALL-scope holders quit, server-scoped lock state for all of them is empty")
        void serverScopeStateEmptyAfterNDistinctHoldersQuit() throws Exception {
            UUID[] holders = soakInsertServerLocks(SOAK_N);

            for (UUID holderUuid : holders) {
                liveManager.onPlayerQuit(holderUuid);
            }

            assertTrue(serverLocksField().isEmpty());
        }

        @Test
        @DisplayName("With a subset of the " + SOAK_N + " ALL-scope holders still online, exactly the "
                + "offline holders' locks are gone and the online holders' locks remain")
        void serverScopeDistinguishesCorrectSweepFromBluntClear() throws Exception {
            UUID[] holders = soakInsertServerLocks(SOAK_N);
            Map<String, UUID> serverLocks = serverLocksField();

            // Odd-indexed holders quit; even-indexed holders remain online.
            for (int i = 1; i < SOAK_N; i += 2) {
                liveManager.onPlayerQuit(holders[i]);
            }

            for (int i = 0; i < SOAK_N; i++) {
                String methodKey = "soak-method-key-" + i;
                if (i % 2 == 1) {
                    assertFalse(serverLocks.containsKey(methodKey),
                            "offline holder #" + i + "'s ALL-scope entry must be removed");
                } else {
                    assertEquals(holders[i], serverLocks.get(methodKey),
                            "still-online holder #" + i + " must retain its ALL-scope entry -- a "
                                    + "blunt clear would wrongly wipe this too");
                }
            }
        }

        /**
         * Triggers lazy first-use registration via one genuine {@code validate()} acquisition,
         * then acquires a distinct SENDER-scope lock for each of {@code n} newly-mocked senders
         * via the real {@code validate()} path.
         */
        private UUID[] soakAcquireSenderLocks(Method method, int n) {
            UUID[] senders = new UUID[n];
            for (int i = 0; i < n; i++) {
                UUID senderUuid = UUID.randomUUID();
                senders[i] = senderUuid;
                Player sender = mock(Player.class);
                lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
                CommandContext context = createPlayerContext(method, sender);
                assertTrue(validator.validate(context).isValid(),
                        "each of the " + n + " senders must be a FIRST, distinct acquisition");
            }
            return senders;
        }

        /**
         * Triggers lazy first-use registration via one genuine SENDER-scope acquisition (ALL-scope
         * entries are inserted directly below since only one real holder can occupy a given
         * method key at a time -- generating n distinct real @UsageLimit(ALL) fixture methods is
         * impractical; the removal predicate under test is identical either way, see
         * PlayerCacheManager's value-side sweep branch), then inserts {@code n} distinct
         * methodKey -> holder entries directly into {@code serverLocks}.
         */
        private UUID[] soakInsertServerLocks(int n) throws Exception {
            Method senderMethod = getClass().getEnclosingClass().getDeclaredMethod("senderLimitedMethod");
            Player registrationTrigger = mock(Player.class);
            lenient().when(registrationTrigger.getUniqueId()).thenReturn(UUID.randomUUID());
            validator.validate(createPlayerContext(senderMethod, registrationTrigger));

            Map<String, UUID> serverLocks = serverLocksField();
            UUID[] holders = new UUID[n];
            for (int i = 0; i < n; i++) {
                UUID holderUuid = UUID.randomUUID();
                holders[i] = holderUuid;
                serverLocks.put("soak-method-key-" + i, holderUuid);
            }
            return holders;
        }
    }
}

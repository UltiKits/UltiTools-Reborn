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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
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
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;

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

    /**
     * Fixtures for the class-level @CmdCD fallback (D-01 follow-up, most-derived-wins) --
     * see {@link com.ultikits.ultitools.utils.ReflectionUtil#resolveMethodOrClassAnnotation}.
     */
    static class MethodLevelOnlyCooldownFixture {
        @CmdCD(4)
        public void methodWithOwnCooldown() { /* Test stub */ }
    }

    @CmdCD(30)
    static class ClassLevelOnlyCooldownFixture {
        public void methodWithoutOwnCooldown() { /* Test stub */ }

        @CmdCD(3)
        public void methodWithOwnCooldownOverride() { /* Test stub */ }
    }

    /**
     * WR-02 (05-REVIEW.md) fixtures: a concrete executor SUBCLASS inheriting an unoverridden
     * {@code @CmdMapping}-shaped method from a superclass -- {@code sharedMethod()}'s
     * declaring class is always {@code SharedCooldownMappingBase}, regardless of which
     * concrete subclass below is used to build the context.
     */
    static class SharedCooldownMappingBase {
        public void sharedMethod() { /* Test stub */ }
    }

    // Combo 1 (the WR-02 broken case): @CmdCD ONLY on the concrete subclass.
    @CmdCD(20)
    static class ConcreteSubclassOnlyCooldownFixture extends SharedCooldownMappingBase {
        // inherits sharedMethod(); only this subclass carries @CmdCD
    }

    // Combo 2: @CmdCD ONLY on the declaring superclass -- pre-WR-02 behaviour, must stay unchanged.
    @CmdCD(15)
    static class DeclaringSuperclassCooldownBase {
        public void sharedMethod() { /* Test stub */ }
    }

    static class ConcreteSubclassNoOwnCooldownFixture extends DeclaringSuperclassCooldownBase {
        // inherits sharedMethod() and the superclass's @CmdCD; declares none of its own
    }

    // Combo 3: BOTH levels carry @CmdCD with DIFFERENT values -- the concrete subclass must win.
    @CmdCD(15)
    static class BothLevelsSuperclassCooldownBase {
        public void sharedMethod() { /* Test stub */ }
    }

    @CmdCD(20)
    static class BothLevelsConcreteSubclassCooldownFixture extends BothLevelsSuperclassCooldownBase {
        // inherits sharedMethod(); both this class and its superclass carry @CmdCD
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

    /**
     * WR-02 overload: also threads the concrete executor class into the context, so
     * {@code CooldownValidator} can resolve a class-level {@code @CmdCD} against it (not just
     * {@code method.getDeclaringClass()}).
     */
    private CommandContext createPlayerContext(Method method, Class<?> executorClass) {
        return CommandContext.builder()
                .sender(mockPlayer)
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

    /**
     * D-01 follow-up (maintainer-required): a class-level {@code @CmdCD} must actually be
     * honoured by {@code CooldownValidator} itself, not just accepted by
     * {@code PluginManager}'s load-time structural check -- otherwise the check's own "pass"
     * is a false assurance (the exact defect class this milestone exists to eliminate).
     * Most-derived-wins: a method-level {@code @CmdCD} takes precedence over a class-level one
     * on the same executor; the class-level value is used only as a fallback when the matched
     * method carries none.
     */
    @Nested
    @DisplayName("Class-level @CmdCD fallback (D-01, most-derived-wins)")
    class ClassLevelFallbackTests {

        @Test
        @DisplayName("Method-level only: applies the method's own cooldown, unchanged")
        void methodLevelOnly_unchanged() throws Exception {
            Method method = MethodLevelOnlyCooldownFixture.class.getDeclaredMethod("methodWithOwnCooldown");
            CommandContext context = createPlayerContext(method);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 1 && remaining <= 5,
                    "Expected the method's own 4s cooldown, but remaining was: " + remaining);
        }

        @Test
        @DisplayName("Class-level only: applies the class's cooldown (new behaviour -- fails on pre-follow-up HEAD)")
        void classLevelOnly_newBehaviorAppliesClassCooldown() throws Exception {
            Method method = ClassLevelOnlyCooldownFixture.class.getDeclaredMethod("methodWithoutOwnCooldown");
            CommandContext context = createPlayerContext(method);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 25 && remaining <= 31,
                    "Expected the class-level 30s cooldown to apply, but remaining was: " + remaining);
        }

        @Test
        @DisplayName("Both present: the method-level value wins over the class-level one")
        void bothPresent_methodLevelWins() throws Exception {
            Method method = ClassLevelOnlyCooldownFixture.class.getDeclaredMethod("methodWithOwnCooldownOverride");
            CommandContext context = createPlayerContext(method);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 1 && remaining <= 4,
                    "Expected the method's own 3s cooldown to win over the class's 30s, but remaining was: "
                            + remaining);
        }
    }

    /**
     * WR-02 (05-REVIEW.md): {@code ClassLevelFallbackTests} above never exercises a class-level
     * {@code @CmdCD} declared on a SUBCLASS whose {@code @CmdMapping} method is inherited
     * (unoverridden) from a superclass -- every fixture there declares its class-level
     * annotation on the SAME class that declares the mapping method. This is exactly the WR-02
     * defect: {@code method.getDeclaringClass()}-only resolution never sees a class-level
     * annotation declared on the concrete SUBCLASS. All four combinations from the review's
     * proof-form rule.
     */
    @Nested
    @DisplayName("WR-02: executor-class-aware @CmdCD fallback (post-review gap closure)")
    class ExecutorClassAwareFallbackTests {

        @Test
        @DisplayName("Concrete subclass only: inherited mapping still cools down (WR-02 broken case)")
        void concreteSubclassOnly_appliesSubclassCooldown() throws Exception {
            Method method = ConcreteSubclassOnlyCooldownFixture.class.getMethod("sharedMethod");
            CommandContext context = createPlayerContext(method, ConcreteSubclassOnlyCooldownFixture.class);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 16 && remaining <= 21,
                    "Expected the concrete subclass's 20s cooldown to apply, but remaining was: " + remaining);
        }

        @Test
        @DisplayName("Declaring superclass only: inherited mapping still cools down (regression pin, unchanged)")
        void declaringSuperclassOnly_stillAppliesSuperclassCooldown() throws Exception {
            Method method = ConcreteSubclassNoOwnCooldownFixture.class.getMethod("sharedMethod");
            CommandContext context = createPlayerContext(method, ConcreteSubclassNoOwnCooldownFixture.class);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 11 && remaining <= 16,
                    "Expected the declaring superclass's 15s cooldown to still apply, but remaining was: "
                            + remaining);
        }

        @Test
        @DisplayName("Both levels present: the concrete subclass's cooldown wins over its superclass's")
        void bothLevelsPresent_concreteSubclassWins() throws Exception {
            Method method = BothLevelsConcreteSubclassCooldownFixture.class.getMethod("sharedMethod");
            CommandContext context = createPlayerContext(method, BothLevelsConcreteSubclassCooldownFixture.class);
            String methodKey = method.toString();

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, methodKey);
            assertTrue(remaining >= 16 && remaining <= 21,
                    "Expected the concrete subclass's 20s cooldown to win over the superclass's 15s, "
                            + "but remaining was: " + remaining);
        }

        @Test
        @DisplayName("Neither level present: falls back to the default cooldown, unchanged")
        void neitherLevelPresent_fallsBackToDefault() throws Exception {
            Method method = SharedCooldownMappingBase.class.getMethod("sharedMethod");
            CommandContext context = createPlayerContext(method, SharedCooldownMappingBase.class);

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, method.toString());
            assertEquals(0, remaining, "No @CmdCD anywhere in the hierarchy -- no cooldown should apply");
        }

        @Test
        @DisplayName("Null executorClass (context built without it): falls back to the pre-WR-02 "
                + "declaring-class-only behaviour")
        void nullExecutorClass_fallsBackToDeclaringClassOnly() throws Exception {
            Method method = ConcreteSubclassOnlyCooldownFixture.class.getMethod("sharedMethod");
            CommandContext context = createPlayerContext(method); // no executorClass set

            validator.applyCooldown(context);

            long remaining = validator.getRemainingCooldown(playerUUID, method.toString());
            assertEquals(0, remaining,
                    "Without executorClass, resolution can only see the declaring superclass, which "
                            + "carries no @CmdCD of its own");
        }
    }

    /**
     * GEN-08 / D-03: {@link #cooldowns} is now registered with the live {@link
     * PlayerCacheManager} (lazy first-use, triggered from {@link CooldownValidator#validate}),
     * so a quitting player's entry is pruned through the REAL quit path --
     * {@link PlayerCacheManager#onPlayerQuit(UUID)}, the same method {@code PluginManager}'s
     * {@code PlayerQuitEvent} listener calls -- rather than by calling {@link
     * CooldownValidator#clearCooldowns(UUID)} directly. These assertions fail on the
     * pre-migration build: nothing was ever registered, so {@code onPlayerQuit} had no tracked
     * field to sweep.
     */
    @Nested
    @DisplayName("PlayerCacheManager quit-based and time-based sweep (GEN-08, D-03)")
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
        private Map<UUID, Map<String, Long>> cooldownsField() throws Exception {
            Field field = CooldownValidator.class.getDeclaredField("cooldowns");
            field.setAccessible(true);
            return (Map<UUID, Map<String, Long>>) field.get(validator);
        }

        @Test
        @DisplayName("A cooldown entry is gone after the player quits, observed through the real quit path")
        void cooldownGoneAfterRealQuitPath() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            // validate() triggers lazy first-use registration with the live manager wired above.
            validator.validate(context);
            validator.applyCooldown(context);
            assertTrue(validator.getRemainingCooldown(playerUUID, method.toString()) > 0);

            liveManager.onPlayerQuit(playerUUID);

            assertEquals(0, validator.getRemainingCooldown(playerUUID, method.toString()));
            assertTrue(cooldownsField().get(playerUUID) == null,
                    "the quitting player's own map entry must be removed, not merely read as expired");
        }

        @Test
        @DisplayName("A still-online player's cooldown survives another player's quit")
        void otherPlayersCooldownUntouchedByQuit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            UUID otherPlayerUuid = UUID.randomUUID();
            Player otherPlayer = mock(Player.class);
            lenient().when(otherPlayer.getUniqueId()).thenReturn(otherPlayerUuid);

            CommandContext context = createPlayerContext(method);
            validator.validate(context);
            validator.applyCooldown(context);

            CommandContext otherContext = CommandContext.builder()
                    .sender(otherPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(method)
                    .build();
            validator.validate(otherContext);
            validator.applyCooldown(otherContext);

            // playerUUID quits; otherPlayerUuid remains online.
            liveManager.onPlayerQuit(playerUUID);

            assertEquals(0, validator.getRemainingCooldown(playerUUID, method.toString()));
            assertTrue(validator.getRemainingCooldown(otherPlayerUuid, method.toString()) > 0,
                    "a sweep triggered by one player's quit must not touch another, still-online player's cooldown");
        }

        @Test
        @DisplayName("An expired cooldown is removed by the time-based sweep, with no quit event")
        void expiredCooldownRemovedByTimeBasedSweepNoQuit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = createPlayerContext(method);

            // Register via lazy first-use, then force the recorded cooldown into the past --
            // deterministic "expired but never swept" without depending on wall-clock waiting.
            validator.validate(context);
            validator.applyCooldown(context);
            cooldownsField().get(playerUUID).put(method.toString(), System.currentTimeMillis() - 1000L);

            // The time-based sweep -- PlayerCacheManager.sweepExpiredEntries() -- NOT a quit event.
            liveManager.sweepExpiredEntries();

            Map<String, Long> remaining = cooldownsField().get(playerUUID);
            assertTrue(remaining == null || remaining.isEmpty(),
                    "cleanupExpired (reached via ExpiringPlayerCache.sweepExpired()) must actually "
                            + "remove the stale entry from the map, not merely make it read as expired");
        }
    }

    /**
     * GEN-08's own acceptance criterion (D-05): "assert both maps' size returns to 0 after 100
     * players quit". N = 100 is the stated floor. Both tests here drive the quit through the
     * real quit path -- {@link PlayerCacheManager#onPlayerQuit(UUID)} -- and Test 2 distinguishes
     * a correct per-player sweep from a blunt clear by keeping a subset of senders online.
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

        @Test
        @DisplayName("After " + SOAK_N + " distinct senders trigger and quit, cooldown state for all of them is empty")
        void stateEmptyAfterNDistinctSendersQuit() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            UUID[] senders = new UUID[SOAK_N];

            for (int i = 0; i < SOAK_N; i++) {
                UUID senderUuid = UUID.randomUUID();
                senders[i] = senderUuid;
                Player sender = mock(Player.class);
                lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
                CommandContext context = CommandContext.builder()
                        .sender(sender).command(mockCommand).alias("test")
                        .rawArgs(new String[]{}).matchedMethod(method).build();
                validator.validate(context);
                validator.applyCooldown(context);
            }

            for (UUID senderUuid : senders) {
                liveManager.onPlayerQuit(senderUuid);
            }

            for (UUID senderUuid : senders) {
                assertEquals(0, validator.getRemainingCooldown(senderUuid, method.toString()));
            }
        }

        @Test
        @DisplayName("With a subset of the " + SOAK_N + " senders still online, exactly the offline "
                + "senders' state is gone and the online senders' state remains")
        void distinguishesCorrectSweepFromBluntClear() throws Exception {
            Method method = getClass().getEnclosingClass().getDeclaredMethod("methodWithCooldown");
            UUID[] senders = new UUID[SOAK_N];

            for (int i = 0; i < SOAK_N; i++) {
                UUID senderUuid = UUID.randomUUID();
                senders[i] = senderUuid;
                Player sender = mock(Player.class);
                lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
                CommandContext context = CommandContext.builder()
                        .sender(sender).command(mockCommand).alias("test")
                        .rawArgs(new String[]{}).matchedMethod(method).build();
                validator.validate(context);
                validator.applyCooldown(context);
            }

            // Odd-indexed senders quit; even-indexed senders remain online.
            for (int i = 1; i < SOAK_N; i += 2) {
                liveManager.onPlayerQuit(senders[i]);
            }

            for (int i = 0; i < SOAK_N; i++) {
                if (i % 2 == 1) {
                    assertEquals(0, validator.getRemainingCooldown(senders[i], method.toString()),
                            "offline sender #" + i + " must have its cooldown removed");
                } else {
                    assertTrue(validator.getRemainingCooldown(senders[i], method.toString()) > 0,
                            "still-online sender #" + i + " must retain its cooldown -- a blunt clear "
                                    + "would wrongly wipe this too");
                }
            }
        }
    }
}

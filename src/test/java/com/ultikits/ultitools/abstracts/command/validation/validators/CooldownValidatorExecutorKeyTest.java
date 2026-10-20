package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.annotations.command.CmdCD;

/**
 * #539: an active {@code @CmdCD} cooldown is keyed by the executor instance as well as the
 * method, so one {@link CooldownValidator} serving two executors of the same class keeps their
 * cooldowns apart.
 * <p>
 * Before the fix the key was {@code method.toString()} alone: a player who used the command
 * through executor A was refused on executor B for A's whole duration, and the reverse.
 */
@DisplayName("CooldownValidator keys active cooldowns by executor instance and method (#539)")
class CooldownValidatorExecutorKeyTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private Player player;
    private UUID playerId;
    private Method mapping;

    /** One executor class, instantiated twice -- the shape two modules sharing a chain produce. */
    public static class SharedExecutorFixture {
        @CmdCD(30)
        public void go() {
            // Test stub: only the annotation matters to the validator.
        }
    }

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);

        player = mock(Player.class);
        playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        mapping = SharedExecutorFixture.class.getMethod("go");
    }

    @AfterEach
    void tearDown() {
        ultiToolsMock.close();
    }

    private CommandContext contextFor(Object executor) {
        return CommandContext.builder()
                .sender(player)
                .command(mock(Command.class))
                .alias("go")
                .rawArgs(new String[]{})
                .matchedMethod(mapping)
                .executorClass(SharedExecutorFixture.class)
                .executor(executor)
                .build();
    }

    @Test
    @DisplayName("a player's use through executor A does not block the same mapping on executor B")
    void useThroughOneExecutorDoesNotBlockTheOther() {
        CooldownValidator shared = new CooldownValidator();
        SharedExecutorFixture executorA = new SharedExecutorFixture();
        SharedExecutorFixture executorB = new SharedExecutorFixture();

        assertTrue(shared.validate(contextFor(executorA)).isValid());
        shared.onComplete(contextFor(executorA), true);

        assertFalse(shared.validate(contextFor(executorA)).isValid(),
                "executor A itself is on cooldown");
        assertTrue(shared.validate(contextFor(executorB)).isValid(),
                "executor B must not inherit executor A's cooldown");

        shared.onComplete(contextFor(executorB), true);
        assertFalse(shared.validate(contextFor(executorB)).isValid(),
                "once used, executor B is on its own cooldown");
    }

    @Test
    @DisplayName("the string-keyed accessors keep working for one executor per validator")
    void stringAccessorsWorkForASingleExecutor() {
        CooldownValidator validator = new CooldownValidator();
        SharedExecutorFixture executor = new SharedExecutorFixture();
        validator.onComplete(contextFor(executor), true);

        long remaining = validator.getRemainingCooldown(playerId, mapping.toString());
        assertTrue(remaining >= 30 && remaining <= 31, "a fresh 30 s cooldown, was " + remaining);

        validator.clearCooldown(playerId, mapping.toString());
        assertEquals(0L, validator.getRemainingCooldown(playerId, mapping.toString()));
        assertTrue(validator.validate(contextFor(executor)).isValid(),
                "clearCooldown(UUID, String) must lift the executor-keyed cooldown");
    }

    @Test
    @DisplayName("the executor-aware accessors address exactly one executor")
    void executorAwareAccessorsAddressOneExecutor() {
        CooldownValidator shared = new CooldownValidator();
        SharedExecutorFixture executorA = new SharedExecutorFixture();
        SharedExecutorFixture executorB = new SharedExecutorFixture();
        shared.onComplete(contextFor(executorA), true);

        assertTrue(shared.getRemainingCooldown(playerId, executorA, mapping.toString()) > 0);
        assertEquals(0L, shared.getRemainingCooldown(playerId, executorB, mapping.toString()));

        shared.onComplete(contextFor(executorB), true);
        shared.clearCooldown(playerId, executorA, mapping.toString());

        assertEquals(0L, shared.getRemainingCooldown(playerId, executorA, mapping.toString()));
        assertTrue(shared.getRemainingCooldown(playerId, executorB, mapping.toString()) > 0,
                "clearing executor A must leave executor B's cooldown in place");
    }

    @Test
    @DisplayName("an active cooldown does not keep an unloaded executor reachable")
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly") // the only way to observe a weak key
    void activeCooldownDoesNotPinTheExecutor() throws InterruptedException {
        CooldownValidator shared = new CooldownValidator();
        WeakReference<SharedExecutorFixture> ref = applyCooldownThroughAThrowawayExecutor(shared);

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertNull(ref.get(), "the validator must not hold the executor strongly while its cooldown runs");
        shared.cleanupExpired();
        assertEquals(0L, shared.getRemainingCooldown(playerId, mapping.toString()),
                "a cooldown whose executor is gone is dropped by the sweep");
    }

    private WeakReference<SharedExecutorFixture> applyCooldownThroughAThrowawayExecutor(CooldownValidator shared) {
        SharedExecutorFixture executor = new SharedExecutorFixture();
        shared.onComplete(contextFor(executor), true);
        assertTrue(shared.getRemainingCooldown(playerId, executor, mapping.toString()) > 0);
        return new WeakReference<>(executor);
    }
}

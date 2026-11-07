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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
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
    @DisplayName("an active cooldown holds only a weak executor reference and sweeps it when cleared")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // inspect the real key, not a parallel test reference
    void activeCooldownDoesNotPinTheExecutor() throws ReflectiveOperationException {
        CooldownValidator shared = new CooldownValidator();
        SharedExecutorFixture executor = new SharedExecutorFixture();
        shared.onComplete(contextFor(executor), true);
        Field cooldownsField = CooldownValidator.class.getDeclaredField("cooldowns");
        cooldownsField.setAccessible(true);
        Map<?, ?> cooldowns = (Map<?, ?>) cooldownsField.get(shared);
        Map<?, ?> methods = (Map<?, ?>) cooldowns.get(playerId);
        assertEquals(1, methods.size(), "control: an active entry exists before reference clearing");
        Object key = methods.keySet().iterator().next();
        Field executorField = key.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        assertTrue(executorField.get(key) instanceof WeakReference,
                "the production key itself must hold the executor weakly");
        WeakReference<?> reference = (WeakReference<?>) executorField.get(key);
        assertTrue(reference.get() == executor, "control: this is the actual executor reference");
        for (Field field : key.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(field.getType().isPrimitive() || field.getType() == String.class
                                || field.getType() == WeakReference.class,
                        "no extra strong reference may pin an executor or its class loader: " + field);
            }
        }
        shared.cleanupExpired();
        assertEquals(1, methods.size(), "a live unexpired cooldown survives a sweep");
        // Simulate the JVM clearing the actual weak key. No GC scheduling or one-second deadline.
        reference.clear();
        assertNull(reference.get());
        shared.cleanupExpired();
        assertTrue(methods.isEmpty(), "the dead key itself must be removed, not just ignored by getters");
        assertTrue(cooldowns.isEmpty(), "the empty player's bucket is also removed");
        assertEquals(0L, shared.getRemainingCooldown(playerId, mapping.toString()));
    }
}

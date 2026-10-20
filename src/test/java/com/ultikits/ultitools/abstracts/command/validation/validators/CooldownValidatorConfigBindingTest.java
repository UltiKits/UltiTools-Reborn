package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.ConfigBoundCooldownState;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.testutil.BindingTimingConfig;

/**
 * #531: a {@code @CmdCD} bound to a module config key. The validator enforces the seconds the
 * framework resolved into it -- at load, and again only by the reload step -- never the
 * annotation literal and never the live field. An unbound {@code @CmdCD} keeps today's per-call
 * {@code value()} read.
 */
@DisplayName("CooldownValidator config-bound @CmdCD (#531)")
class CooldownValidatorConfigBindingTest {

    private MockedStatic<UltiTools> mockedUltiTools;
    private Player player;
    private Command command;
    private CooldownValidator validator;
    /** The executor instance that owns the bound-cooldown state in these contexts. */
    private UnresolvedBoundExecutor holder;

    @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
    public void boundMapping() {
        // Fixture: annotation carrier only.
    }

    @CmdCD(5)
    public void literalMapping() {
        // Fixture: annotation carrier only.
    }

    @BeforeEach
    void setUp() {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(ultiTools);
        player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        command = mock(Command.class);
        validator = new CooldownValidator();
        holder = new UnresolvedBoundExecutor();
    }

    @AfterEach
    void tearDown() {
        mockedUltiTools.close();
    }

    private CommandContext contextFor(Method method) {
        return CommandContext.builder()
                .sender(player)
                .command(command)
                .alias("test")
                .rawArgs(new String[]{})
                .matchedMethod(method)
                .executorClass(getClass())
                .executor(holder)
                .build();
    }

    private Method mapping(String name) throws NoSuchMethodException {
        return getClass().getMethod(name);
    }

    private String boundKey() throws NoSuchMethodException {
        return CooldownValidator.bindingKey(mapping("boundMapping").getAnnotation(CmdCD.class));
    }

    /**
     * Codex round 6 on #536, answered by changing route: a {@code CooldownValidator} can be shared by
     * several executors -- even of several modules -- so it must hold no module state at all. Every
     * lifecycle finding of rounds 1, 2, 5 and 6 came from bound-cooldown state kept in the validator
     * (keyed by binding, then class, then instance; released on unload, then on failed load). The
     * state now lives on the owning executor. This pins the validator's instance fields to its own
     * per-player cooldown bookkeeping; adding a field here means re-reading that history first.
     */
    @Test
    @DisplayName("a CooldownValidator holds no module-owned state: only its own per-player cooldown bookkeeping")
    void aCooldownValidatorHoldsNoModuleState() {
        java.util.Set<String> instanceFields = new java.util.TreeSet<>();
        for (java.lang.reflect.Field field : CooldownValidator.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                instanceFields.add(field.getName());
            }
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(
                        "cooldowns", "playerCacheRegistered", "defaultCooldownSeconds")),
                instanceFields);
    }

    @Test
    @DisplayName("the new @CmdCD elements default to unbound, and bindingKey is null for an unbound annotation")
    void newCmdCdElementsDefaultToUnbound() throws NoSuchMethodException {
        CmdCD literal = mapping("literalMapping").getAnnotation(CmdCD.class);
        assertSame(AbstractConfigEntity.class, literal.config());
        assertEquals("", literal.key());
        assertNull(CooldownValidator.bindingKey(literal));
        assertEquals(BindingTimingConfig.class.getName() + "#cooldown.wild", boundKey());
    }

    @Test
    @DisplayName("a bound cooldown enforces the resolved seconds, not the annotation literal")
    void aBoundCooldownEnforcesTheResolvedSeconds() throws NoSuchMethodException {
        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 7));
        Method method = mapping("boundMapping");

        validator.onComplete(contextFor(method), true);

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        // getRemainingCooldown rounds up and adds one second, so a fresh 7 s cooldown reads 7 or 8.
        assertTrue(remaining >= 7 && remaining <= 8, "expected about 7 s, was " + remaining);
        assertTrue(!validator.validate(contextFor(method)).isValid(), "a second use within 7 s is refused");
    }

    @Test
    @DisplayName("a bound cooldown the framework never resolved is a clean command failure plus a SEVERE log, never an exception")
    void anUnresolvedBoundCooldownIsACleanFailure() throws NoSuchMethodException {
        Method method = mapping("boundMapping");
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // Records are appended straight to the in-memory list.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        Logger logger = Logger.getLogger(CooldownValidator.class.getName());
        logger.addHandler(capture);
        try {
            CommandValidator.ValidationResult result = assertDoesNotThrow(() -> validator.validate(contextFor(method)));

            assertFalse(result.isValid(), "an unresolved binding must fail closed, not mean no cooldown");
            assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
            assertTrue(records.stream().anyMatch(r -> Level.SEVERE.equals(r.getLevel())
                    && r.getMessage().contains("cooldown.wild")), String.valueOf(records));
        } finally {
            logger.removeHandler(capture);
        }
    }

    /** A never-resolved executor: constructed directly, not through a module load. */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"fw531unresolved"})
    public static class UnresolvedBoundExecutor extends BaseCommandExecutor {
        public int runs;

        @Override
        protected void handleHelp(CommandSender sender) {
            // Test stub - not exercised
        }

        @CmdMapping(format = "go")
        @CmdCD(config = BindingTimingConfig.class, key = "cooldown.wild")
        public void doGo(Player sender) {
            runs++;
        }
    }

    @Test
    @DisplayName("through onCommand, an unresolved binding refuses the command with a message and throws nothing")
    void anUnresolvedBindingThroughOnCommandThrowsNothing() {
        UnresolvedBoundExecutor executor = new UnresolvedBoundExecutor();
        when(command.getName()).thenReturn("fw531unresolved");

        boolean handled = assertDoesNotThrow(() -> executor.onCommand(player, command, "fw531unresolved",
                new String[]{"go"}));

        assertTrue(handled);
        assertEquals(0, executor.runs, "the mapped method must not run");
        verify(player, atLeastOnce()).sendMessage(anyString());
    }

    /**
     * The dispatch path for a RESOLVED binding: {@code BaseCommandExecutor#onCommand} must put
     * itself into the context ({@code CommandContext.executor}), because the validator reads the
     * bound seconds from the dispatching executor. With a bound cooldown already running, a real
     * {@code onCommand} must be refused with the COOLDOWN message; without the executor in the
     * context the binding would read as unresolved and fail closed with a different message.
     * (Validation runs before the command body is deferred with {@code runTask}, so no server is
     * needed.)
     */
    @Test
    @DisplayName("through onCommand, a resolved bound cooldown that is running refuses with the cooldown message")
    void aResolvedBindingThroughOnCommandRefusesWithTheCooldownMessage() throws NoSuchMethodException {
        UnresolvedBoundExecutor executor = new UnresolvedBoundExecutor();
        ConfigBoundCooldownState.setSeconds(executor, Collections.singletonMap(boundKey(), 30));
        Method mapping = UnresolvedBoundExecutor.class.getMethod("doGo", Player.class);
        CooldownValidator chainValidator = (CooldownValidator) executor.getValidatorChain().getValidators().stream()
                .filter(v -> v instanceof CooldownValidator).findFirst()
                .orElseThrow(() -> new AssertionError("the default chain carries a CooldownValidator"));
        chainValidator.onComplete(CommandContext.builder()
                .sender(player).command(command).alias("fw531unresolved").rawArgs(new String[]{"go"})
                .matchedMethod(mapping).executorClass(UnresolvedBoundExecutor.class).executor(executor)
                .build(), true);
        when(command.getName()).thenReturn("fw531unresolved");

        boolean handled = executor.onCommand(player, command, "fw531unresolved", new String[]{"go"});

        assertTrue(handled);
        assertEquals(0, executor.runs, "the running cooldown refuses the command");
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("操作频繁"));
    }

    @Test
    @DisplayName("an unbound cooldown still reads the annotation value, whatever the bound cache holds")
    void anUnboundCooldownStillReadsTheAnnotationValue() throws NoSuchMethodException {
        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 99));
        Method method = mapping("literalMapping");

        validator.onComplete(contextFor(method), true);

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        assertTrue(remaining >= 5 && remaining <= 6, "expected about 5 s (rounded up, plus one), was " + remaining);
    }

    @Test
    @DisplayName("a refreshed value does not shorten a cooldown that is already running")
    void aRefreshedValueDoesNotShortenARunningCooldown() throws NoSuchMethodException {
        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 60));
        Method method = mapping("boundMapping");
        validator.onComplete(contextFor(method), true);

        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 5));

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        assertTrue(remaining > 50, "the running cooldown keeps the end time it was stamped with, was " + remaining);
    }

    /**
     * Module gate-1 review (UltiEssentials round 2, WR-03): a bound value refreshed to {@code 0} must
     * not lift a cooldown that is already running. {@code 0} means that no new cooldown is stamped; a
     * stamp made earlier keeps its end time and expires on its own, whatever the current value is.
     */
    @Test
    @DisplayName("a refresh to 0 keeps a running cooldown: a stored, unexpired end time is honoured at any value")
    void aRefreshToZeroKeepsARunningCooldown() throws NoSuchMethodException {
        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 60));
        Method method = mapping("boundMapping");
        validator.onComplete(contextFor(method), true);

        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 0));

        assertFalse(validator.validate(contextFor(method)).isValid(),
                "the running 60 s cooldown still refuses the command after the value became 0");
        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        assertTrue(remaining > 50 && remaining <= 61, "the end time is unchanged, was " + remaining);
    }

    @Test
    @DisplayName("a bound value of 0 stamps no new cooldown")
    void aZeroValueStampsNoNewCooldown() throws NoSuchMethodException {
        ConfigBoundCooldownState.setSeconds(holder, Collections.singletonMap(boundKey(), 0));
        Method method = mapping("boundMapping");

        validator.onComplete(contextFor(method), true);

        assertEquals(0L, validator.getRemainingCooldown(player.getUniqueId(), method.toString()));
        assertTrue(validator.validate(contextFor(method)).isValid(), "no cooldown at 0");
    }
}

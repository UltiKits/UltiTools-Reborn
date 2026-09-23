package com.ultikits.ultitools.abstracts.command.validation.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.annotations.command.CmdCD;
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
                .build();
    }

    private Method mapping(String name) throws NoSuchMethodException {
        return getClass().getMethod(name);
    }

    private String boundKey() throws NoSuchMethodException {
        return CooldownValidator.bindingKey(mapping("boundMapping").getAnnotation(CmdCD.class));
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
        validator.setBoundCooldownSeconds(Collections.singletonMap(boundKey(), 7));
        Method method = mapping("boundMapping");

        validator.onComplete(contextFor(method), true);

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        // getRemainingCooldown rounds up and adds one second, so a fresh 7 s cooldown reads 7 or 8.
        assertTrue(remaining >= 7 && remaining <= 8, "expected about 7 s, was " + remaining);
        assertTrue(!validator.validate(contextFor(method)).isValid(), "a second use within 7 s is refused");
    }

    @Test
    @DisplayName("a bound cooldown the framework never resolved fails loudly, naming the key")
    void anUnresolvedBoundCooldownFailsLoudly() throws NoSuchMethodException {
        Method method = mapping("boundMapping");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator.validate(contextFor(method)));

        assertTrue(failure.getMessage().contains("cooldown.wild"), failure.getMessage());
    }

    @Test
    @DisplayName("an unbound cooldown still reads the annotation value, whatever the bound cache holds")
    void anUnboundCooldownStillReadsTheAnnotationValue() throws NoSuchMethodException {
        validator.setBoundCooldownSeconds(Collections.singletonMap(boundKey(), 99));
        Method method = mapping("literalMapping");

        validator.onComplete(contextFor(method), true);

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        assertTrue(remaining >= 5 && remaining <= 6, "expected about 5 s (rounded up, plus one), was " + remaining);
    }

    @Test
    @DisplayName("a refreshed value does not shorten a cooldown that is already running")
    void aRefreshedValueDoesNotShortenARunningCooldown() throws NoSuchMethodException {
        validator.setBoundCooldownSeconds(Collections.singletonMap(boundKey(), 60));
        Method method = mapping("boundMapping");
        validator.onComplete(contextFor(method), true);

        validator.setBoundCooldownSeconds(Collections.singletonMap(boundKey(), 5));

        long remaining = validator.getRemainingCooldown(player.getUniqueId(), method.toString());
        assertTrue(remaining > 50, "the running cooldown keeps the end time it was stamped with, was " + remaining);
    }
}

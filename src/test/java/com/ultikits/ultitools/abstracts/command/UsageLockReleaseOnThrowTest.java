package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;
import com.ultikits.ultitools.abstracts.command.parser.TypeParserRegistry;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * #568, gate-1 finding A-P2-1: a dispatch that fails by an EXCEPTION after the {@code @UsageLimit}
 * lock was taken -- a validator ordered after the lock throws, or a module's own {@link TypeParser}
 * throws something other than {@code TypeParseException} -- must release the lock too, exactly as a
 * refusal by return does. And one validator's throwing hook must not keep a later validator's hook
 * (the lock release) from running.
 */
@DisplayName("A dispatch that fails by an exception releases the @UsageLimit lock (#568, gate 1)")
class UsageLockReleaseOnThrowTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;
    private Player player;
    private TypeParser<Widget> throwingParser;

    /** A parameter type whose module parser throws a plain runtime exception on bad input. */
    public static final class Widget {
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"guarded"})
    static class GuardedExecutor extends BaseCommandExecutor {
        final AtomicInteger bodies = new AtomicInteger();

        GuardedExecutor() {
            super();
        }

        GuardedExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @UsageLimit(UsageLimit.LimitType.ALL)
        @CmdMapping(format = "go")
        public void go(CommandSender sender) {
            bodies.incrementAndGet();
        }

        @UsageLimit(UsageLimit.LimitType.ALL)
        @CmdMapping(format = "widget <w>")
        public void widget(CommandSender sender, @CmdParam("w") Widget w) {
            bodies.incrementAndGet();
        }
    }

    /** Throws from validate(), ordered after the lock validator. */
    static final class ThrowingValidator implements CommandValidator {
        boolean armed = true;

        @Override
        public ValidationResult validate(CommandContext context) {
            if (armed) {
                throw new IllegalStateException("validator failed");
            }
            return ValidationResult.success();
        }

        @Override
        public int getOrder() {
            return 400;
        }
    }

    /** Passes, then throws from its refusal hook; ordered BEFORE the lock validator. */
    static final class ThrowingHookValidator implements CommandValidator {
        @Override
        public ValidationResult validate(CommandContext context) {
            return ValidationResult.success();
        }

        @Override
        public int getOrder() {
            return 100;
        }

        @Override
        public void onRefused(CommandContext context) {
            throw new IllegalStateException("hook failed");
        }
    }

    /** Refuses by return, ordered after the lock validator. */
    static final class RefusingValidator implements CommandValidator {
        @Override
        public ValidationResult validate(CommandContext context) {
            return ValidationResult.failure("refused");
        }

        @Override
        public int getOrder() {
            return 400;
        }
    }

    @BeforeEach
    void setUp() {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);
        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("guarded");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        throwingParser = new TypeParser<Widget>() {
            @Override
            public List<Class<?>> getSupportedTypes() {
                return Collections.singletonList(Widget.class);
            }

            @Override
            public Class<Widget> getPrimaryType() {
                return Widget.class;
            }

            @Override
            public Widget parse(String value) {
                throw new IllegalArgumentException("no such widget: " + value);
            }
        };
        TypeParserRegistry.getInstance().register(throwingParser);
    }

    @AfterEach
    void tearDown() {
        TypeParserRegistry.getInstance().unregister(throwingParser);
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private static String goKey() throws NoSuchMethodException {
        return GuardedExecutor.class.getMethod("go", CommandSender.class).toString();
    }

    @Test
    @DisplayName("a validator after the lock throws: the exception propagates and the lock is released")
    void throwingLaterValidatorReleasesTheLock() throws NoSuchMethodException {
        ThrowingValidator throwing = new ThrowingValidator();
        UsageLockValidator lock = new UsageLockValidator();
        GuardedExecutor executor = new GuardedExecutor(ValidatorChain.builder().add(lock).add(throwing).build());

        assertThrows(IllegalStateException.class,
                () -> executor.onCommand(player, command, "guarded", new String[]{"go"}));

        assertFalse(lock.isLocked(player.getUniqueId(), goKey()), "the server-wide lock must not stay held");
        throwing.armed = false;
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        executor.onCommand(other, command, "guarded", new String[]{"go"});
        assertEquals(1, executor.bodies.get(), "another player's call runs");
    }

    @Test
    @DisplayName("a module parser throws a plain runtime exception: the lock is released")
    void throwingModuleParserReleasesTheLock() {
        GuardedExecutor executor = new GuardedExecutor();

        assertThrows(IllegalArgumentException.class,
                () -> executor.onCommand(player, command, "guarded", new String[]{"widget", "nope"}));

        executor.onCommand(player, command, "guarded", new String[]{"go"});
        assertEquals(1, executor.bodies.get(), "a later call of a lock-guarded mapping by anyone runs");
        assertFalse(executor.getLockValidator().isLocked(player.getUniqueId(), widgetKey()));
    }

    @Test
    @DisplayName("an earlier validator's throwing refusal hook does not keep the lock from being released")
    void throwingHookDoesNotSkipTheLockRelease() throws NoSuchMethodException {
        UsageLockValidator lock = new UsageLockValidator();
        GuardedExecutor executor = new GuardedExecutor(ValidatorChain.builder()
                .add(new ThrowingHookValidator()).add(lock).add(new RefusingValidator()).build());

        assertThrows(IllegalStateException.class,
                () -> executor.onCommand(player, command, "guarded", new String[]{"go"}));

        assertFalse(lock.isLocked(player.getUniqueId(), goKey()), "the lock validator's hook must still run");
    }

    private static String widgetKey() {
        try {
            return GuardedExecutor.class.getMethod("widget", CommandSender.class, Widget.class).toString();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}

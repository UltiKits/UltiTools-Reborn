package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * #568: a {@code @UsageLimit} lock is acquired during validation, so a dispatch refused after the
 * lock validator ran -- by a later validator (the cooldown), the argument-count check or parameter
 * parsing -- must release it. Before the fix none of those paths did, and the lock stayed held
 * until the player quit: every later call of the mapping was refused with "wait for the previous
 * command".
 */
@DisplayName("A refused dispatch releases the @UsageLimit lock it acquired (#568)")
class UsageLockReleaseOnRefusalTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;
    private Player player;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"locked"})
    static class LockedExecutor extends BaseCommandExecutor {
        final AtomicInteger cooled = new AtomicInteger();
        final AtomicInteger numbered = new AtomicInteger();
        final AtomicInteger paired = new AtomicInteger();

        LockedExecutor() {
            super();
        }

        LockedExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @UsageLimit(UsageLimit.LimitType.SENDER)
        @CmdCD(5)
        @CmdMapping(format = "cooled")
        public void cooled(CommandSender sender) {
            cooled.incrementAndGet();
        }

        @UsageLimit(UsageLimit.LimitType.ALL)
        @CmdMapping(format = "num <n>")
        public void numbered(CommandSender sender, @CmdParam("n") Integer n) {
            numbered.incrementAndGet();
        }

        @UsageLimit(UsageLimit.LimitType.SENDER)
        @CmdMapping(format = "pair <a> <b>")
        public void paired(CommandSender sender, @CmdParam("a") String a, @CmdParam("b") String b) {
            paired.incrementAndGet();
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
        lenient().when(command.getName()).thenReturn("locked");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private static String key(String name, Class<?>... parameterTypes) {
        try {
            return LockedExecutor.class.getMethod(name, parameterTypes).toString();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("refused by the cooldown after the lock was taken: the lock is released")
    void cooldownRefusalReleasesTheLock() {
        LockedExecutor executor = new LockedExecutor();

        executor.onCommand(player, command, "locked", new String[]{"cooled"});
        executor.onCommand(player, command, "locked", new String[]{"cooled"});

        assertEquals(1, executor.cooled.get(), "the second call is refused by the cooldown");
        assertFalse(executor.getLockValidator().isLocked(player.getUniqueId(), key("cooled", CommandSender.class)),
                "the cooldown's refusal must not leave the lock held");
    }

    @Test
    @DisplayName("refused by parameter parsing: the lock is released and the next valid call runs")
    void parseFailureReleasesTheLock() {
        LockedExecutor executor = new LockedExecutor();

        executor.onCommand(player, command, "locked", new String[]{"num", "not-a-number"});
        executor.onCommand(player, command, "locked", new String[]{"num", "3"});

        assertEquals(1, executor.numbered.get(), "a mistyped argument must not lock the mapping for everyone");
    }

    @Test
    @DisplayName("refused by the argument-count check: the lock is released and the next valid call runs")
    void argumentCountRefusalReleasesTheLock() {
        LockedExecutor executor = new LockedExecutor();

        executor.onCommand(player, command, "locked", new String[]{"pair", "x"});
        executor.onCommand(player, command, "locked", new String[]{"pair", "x", "y"});

        assertEquals(1, executor.paired.get());
    }

    @Test
    @DisplayName("a refused dispatch notifies only the validators that passed, and applies no cooldown")
    void refusalNotifiesOnlyPassedValidatorsAndAppliesNoCooldown() {
        List<String> events = new ArrayList<>();
        CommandValidator passing = new RecordingValidator("passing", 10, true, events);
        CommandValidator refusing = new RecordingValidator("refusing", 20, false, events);
        CommandValidator unreached = new RecordingValidator("unreached", 30, true, events);
        LockedExecutor executor = new LockedExecutor(
                ValidatorChain.builder().add(passing).add(refusing).add(unreached).build());

        executor.onCommand(player, command, "locked", new String[]{"cooled"});

        assertEquals(0, executor.cooled.get());
        assertEquals(java.util.Arrays.asList("validate passing", "validate refusing", "refused passing"), events,
                "only a validator that passed is told of the refusal, and no onComplete runs");
    }

    /** Records validate/onComplete/onRefused calls in order. */
    static final class RecordingValidator implements CommandValidator {
        private final String name;
        private final int order;
        private final boolean pass;
        private final List<String> events;

        RecordingValidator(String name, int order, boolean pass, List<String> events) {
            this.name = name;
            this.order = order;
            this.pass = pass;
            this.events = events;
        }

        @Override
        public ValidationResult validate(CommandContext context) {
            events.add("validate " + name);
            return pass ? ValidationResult.success() : ValidationResult.failure("refused by " + name);
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void onComplete(CommandContext context, boolean commandSucceeded) {
            events.add("complete " + name);
        }

        @Override
        public void onRefused(CommandContext context) {
            events.add("refused " + name);
        }
    }
}

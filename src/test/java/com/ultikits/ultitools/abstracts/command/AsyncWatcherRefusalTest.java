package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * Local Codex review of #570, run 2: an {@code @AsyncCommand} with a timeout submits its body first
 * and arms the timeout watcher second. When the scheduler refuses only the watcher (the plugin is
 * disabling between the two calls), the body is already accepted and will run its {@code onComplete}
 * hooks, so the refusal hooks must not run: they would release the {@code @UsageLimit} lock the body
 * still holds, and the body's completion would then release it a second time.
 */
@DisplayName("A refused timeout watcher does not refuse an async body already submitted (#568, Codex run 2)")
class AsyncWatcherRefusalTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private final List<Runnable> asyncBodies = new ArrayList<>();
    private Command command;
    private Player player;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"slowcmd"})
    static class SlowExecutor extends BaseCommandExecutor {
        final AtomicInteger bodies = new AtomicInteger();

        SlowExecutor(ValidatorChain chain) {
            super(chain);
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @AsyncCommand(timeout = 5)
        @UsageLimit(UsageLimit.LimitType.ALL)
        @CmdMapping(format = "slow")
        public void slow(CommandSender sender) {
            bodies.incrementAndGet();
        }
    }

    /** Passes and counts its refusal and completion hooks. */
    static final class CountingValidator implements CommandValidator {
        final AtomicInteger refusals = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();

        @Override
        public ValidationResult validate(CommandContext context) {
            return ValidationResult.success();
        }

        @Override
        public int getOrder() {
            return 400;
        }

        @Override
        public void onRefused(CommandContext context) {
            refusals.incrementAndGet();
        }

        @Override
        public void onComplete(CommandContext context, boolean commandSucceeded) {
            completions.incrementAndGet();
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
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);
        lenient().when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    asyncBodies.add(invocation.getArgument(1));
                    return null;
                });
        lenient().when(scheduler.runTaskLaterAsynchronously(any(Plugin.class), any(Runnable.class), anyLong()))
                .thenThrow(new IllegalPluginAccessException("Plugin attempted to register task while disabled"));
        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("slowcmd");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    @Test
    @DisplayName("the watcher is refused after the body was submitted: no refusal hook runs, the body keeps the lock")
    void refusedWatcherLeavesTheSubmittedBodyItsLock() throws NoSuchMethodException {
        UsageLockValidator lock = new UsageLockValidator();
        CountingValidator counting = new CountingValidator();
        SlowExecutor executor = new SlowExecutor(ValidatorChain.builder().add(lock).add(counting).build());
        String key = SlowExecutor.class.getMethod("slow", CommandSender.class).toString();

        assertThrows(IllegalPluginAccessException.class,
                () -> executor.onCommand(player, command, "slowcmd", new String[]{"slow"}));

        assertEquals(1, asyncBodies.size(), "the body was accepted by the scheduler");
        assertEquals(0, counting.refusals.get(), "a submitted body is not a refused dispatch");
        assertTrue(lock.isLocked(player.getUniqueId(), key), "the body, still pending, holds its lock");

        asyncBodies.get(0).run();

        assertEquals(1, executor.bodies.get());
        assertEquals(1, counting.completions.get(), "the body's completion hooks run once");
        assertFalse(lock.isLocked(player.getUniqueId(), key), "the body releases the lock when it completes");
    }
}

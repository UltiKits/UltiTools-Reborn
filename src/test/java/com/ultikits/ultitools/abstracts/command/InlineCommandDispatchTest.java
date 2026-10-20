package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * #541: a synchronous command body runs at the moment the command is dispatched when that happens
 * on the server's primary thread, for every sender, and is deferred with {@code runTask} only when
 * {@code onCommand} is not on the primary thread (the maintainer's answer of 2026-09-29).
 * <p>
 * Before the fix every synchronous body was deferred one tick. Paper 1.21.11's command block
 * records output only while its dispatch is open, so the body's replies to a command block or a
 * command minecart were lost; the same deferral let two dispatches in one tick both pass
 * {@code @CmdCD}, because the cooldown was recorded only when the deferred body finished.
 * <p>
 * The scheduler here captures every task and runs none of them, so "the body ran before
 * {@code onCommand} returned" is observable directly.
 */
@DisplayName("Command bodies run at dispatch on the primary thread (#541)")
class InlineCommandDispatchTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private BukkitScheduler scheduler;
    private final List<Runnable> deferred = new ArrayList<>();
    private Command command;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"replyprobe"})
    static class ReplyingExecutor extends BaseCommandExecutor {
        final AtomicInteger bodies = new AtomicInteger();
        final AtomicInteger cooledBodies = new AtomicInteger();
        final AtomicInteger asyncBodies = new AtomicInteger();

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void reply(CommandSender sender) {
            bodies.incrementAndGet();
            sender.sendMessage("module reply");
        }

        @CmdCD(5)
        @CmdMapping(format = "cooled")
        public void cooled(CommandSender sender) {
            cooledBodies.incrementAndGet();
        }

        @AsyncCommand(showProcessing = false, timeout = 0)
        @CmdMapping(format = "async")
        public void async(CommandSender sender) {
            asyncBodies.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);

        scheduler = mock(BukkitScheduler.class);
        lenient().when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            deferred.add(inv.getArgument(1));
            return null;
        });
        lenient().when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
            deferred.add(inv.getArgument(1));
            return null;
        });
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(true);

        command = mock(Command.class);
        lenient().when(command.getName()).thenReturn("replyprobe");
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private void assertReplyInsideDispatch(CommandSender sender) {
        ReplyingExecutor executor = new ReplyingExecutor();

        executor.onCommand(sender, command, "replyprobe", new String[]{});

        assertEquals(1, executor.bodies.get(), "the body must have run before onCommand returned");
        verify(sender).sendMessage("module reply");
        verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("a command block receives the body's reply while its dispatch is open")
    void commandBlockReceivesTheReplyInsideTheDispatch() {
        assertReplyInsideDispatch(mock(BlockCommandSender.class));
    }

    @Test
    @DisplayName("a command minecart receives the body's reply while its dispatch is open")
    void commandMinecartReceivesTheReplyInsideTheDispatch() {
        assertReplyInsideDispatch(mock(CommandMinecart.class));
    }

    @Test
    @DisplayName("the console receives the body's reply while its dispatch is open")
    void consoleReceivesTheReplyInsideTheDispatch() {
        assertReplyInsideDispatch(mock(ConsoleCommandSender.class));
    }

    @Test
    @DisplayName("a player receives the body's reply while the dispatch is open")
    void playerReceivesTheReplyInsideTheDispatch() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        assertReplyInsideDispatch(player);
    }

    @Test
    @DisplayName("off the primary thread the body is handed to runTask and has not run when onCommand returns")
    void offThePrimaryThreadTheBodyIsDeferred() {
        bukkitMock.when(Bukkit::isPrimaryThread).thenReturn(false);
        ReplyingExecutor executor = new ReplyingExecutor();
        BlockCommandSender block = mock(BlockCommandSender.class);

        executor.onCommand(block, command, "replyprobe", new String[]{});

        assertEquals(0, executor.bodies.get(), "the body must wait for the main thread");
        verify(scheduler, times(1)).runTask(any(Plugin.class), any(Runnable.class));
        deferred.forEach(Runnable::run);
        assertEquals(1, executor.bodies.get());
        verify(block).sendMessage("module reply");
    }

    @Test
    @DisplayName("two dispatches of a @CmdCD command in the same tick: the second meets the cooldown")
    void twoDispatchesInOneTickMeetTheCooldown() {
        ReplyingExecutor executor = new ReplyingExecutor();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        executor.onCommand(player, command, "replyprobe", new String[]{"cooled"});
        executor.onCommand(player, command, "replyprobe", new String[]{"cooled"});
        // Anything the dispatches deferred runs "on the next tick" -- after both have returned.
        new ArrayList<>(deferred).forEach(Runnable::run);

        assertEquals(1, executor.cooledBodies.get(),
                "the second dispatch in the same tick must be refused by the cooldown");
    }

    @Test
    @DisplayName("an @AsyncCommand mapping keeps the async path even on the primary thread")
    void asyncMappingKeepsTheAsyncPath() {
        ReplyingExecutor executor = new ReplyingExecutor();

        executor.onCommand(mock(ConsoleCommandSender.class), command, "replyprobe", new String[]{"async"});

        assertEquals(0, executor.asyncBodies.get(), "an async body never runs on the dispatching thread");
        verify(scheduler, times(1)).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    }

    @Test
    @DisplayName("running inline changes no gate: a command block without the permission is still refused")
    void inlineDispatchStillRunsTheValidatorChain() {
        PermissionedExecutor executor = new PermissionedExecutor();
        BlockCommandSender block = mock(BlockCommandSender.class);
        when(block.hasPermission("probe.use")).thenReturn(false);

        executor.onCommand(block, command, "guarded", new String[]{});

        assertEquals(0, executor.bodies.get(), "a refused sender's body must never run");
        assertTrue(deferred.isEmpty(), "nothing is scheduled for a refused dispatch");
    }

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"guarded"}, permission = "probe.use")
    static class PermissionedExecutor extends BaseCommandExecutor {
        final AtomicInteger bodies = new AtomicInteger();

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void guarded(CommandSender sender) {
            bodies.incrementAndGet();
        }
    }
}

package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;

/**
 * #541: what {@code @UsageLimit} does when a command body re-enters its own command inline.
 * <p>
 * Command bodies run at dispatch on the primary thread, so a body that dispatches its own
 * command runs the nested dispatch while its own lock is still held. Measured behaviour, pinned
 * here and stated in {@link UsageLimit}'s javadoc and {@code COMPATIBILITY.md}: the nested
 * dispatch is refused with the ordinary "wait for the previous command" message -- the lock is a
 * non-blocking check, so nothing waits and nothing deadlocks -- and the outer body's lock is
 * released when the outer body returns, normally or by throwing.
 */
@DisplayName("@UsageLimit when a body re-enters its own command inline (#541)")
class UsageLimitReentryTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;

    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"reenter"})
    static class ReenteringExecutor extends BaseCommandExecutor {
        final AtomicInteger senderBodies = new AtomicInteger();
        final AtomicInteger allBodies = new AtomicInteger();
        Command command;
        CommandSender nestedSender;
        boolean throwAfterReentry;

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @UsageLimit(UsageLimit.LimitType.SENDER)
        @CmdMapping(format = "sender")
        public void senderScoped(CommandSender sender) {
            if (senderBodies.incrementAndGet() == 1) {
                onCommand(nestedSender == null ? sender : nestedSender, command, "reenter", new String[]{"sender"});
                if (throwAfterReentry) {
                    throw new IllegalStateException("outer body failed after re-entering");
                }
            }
        }

        @UsageLimit(UsageLimit.LimitType.ALL)
        @CmdMapping(format = "all")
        public void allScoped(CommandSender sender) {
            if (allBodies.incrementAndGet() == 1) {
                onCommand(nestedSender == null ? sender : nestedSender, command, "reenter", new String[]{"all"});
                if (throwAfterReentry) {
                    throw new IllegalStateException("outer body failed after re-entering");
                }
            }
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
        lenient().when(command.getName()).thenReturn("reenter");
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private ReenteringExecutor executor() {
        ReenteringExecutor executor = new ReenteringExecutor();
        executor.command = command;
        return executor;
    }

    @Test
    @DisplayName("SENDER: the nested call is refused, and the lock is free once the outer body returns")
    void senderScopeRefusesTheNestedCallAndReleases() {
        ReenteringExecutor executor = executor();
        Player player = player();

        executor.onCommand(player, command, "reenter", new String[]{"sender"});

        assertEquals(1, executor.senderBodies.get(), "only the outer body ran; the nested call was refused");
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("请先等待上一条命令执行完毕"));
        assertFalse(executor.getLockValidator().isLocked(player.getUniqueId(),
                        pathKey("senderScoped")),
                "no lock stays held after the outer body returns");
    }

    @Test
    @DisplayName("SENDER: an outer body that throws after re-entering still releases its lock")
    void senderScopeReleasesWhenTheOuterBodyThrows() {
        ReenteringExecutor executor = executor();
        executor.throwAfterReentry = true;
        Player player = player();

        executor.onCommand(player, command, "reenter", new String[]{"sender"});

        assertFalse(executor.getLockValidator().isLocked(player.getUniqueId(), pathKey("senderScoped")));
    }

    @Test
    @DisplayName("ALL: the nested call is refused, from the same player or from the console")
    void allScopeRefusesTheNestedCallFromAnySender() {
        ReenteringExecutor samePlayer = executor();
        Player player = player();
        samePlayer.onCommand(player, command, "reenter", new String[]{"all"});
        assertEquals(1, samePlayer.allBodies.get());

        ReenteringExecutor viaConsole = executor();
        viaConsole.nestedSender = mock(ConsoleCommandSender.class);
        Player other = player();
        viaConsole.onCommand(other, command, "reenter", new String[]{"all"});
        assertEquals(1, viaConsole.allBodies.get(), "the console's nested call meets the player's server-wide lock");
        verify(viaConsole.nestedSender).sendMessage(
                org.mockito.ArgumentMatchers.contains("请先等待其他玩家发送的命令执行完毕"));
        assertFalse(viaConsole.getLockValidator().isLocked(other.getUniqueId(), pathKey("allScoped")),
                "the server-wide lock is released when the outer body returns");
    }

    @Test
    @DisplayName("ALL: an outer body that throws after re-entering still releases its lock, and a later call runs")
    void allScopeReleasesWhenTheOuterBodyThrows() {
        ReenteringExecutor executor = executor();
        executor.throwAfterReentry = true;
        Player player = player();

        executor.onCommand(player, command, "reenter", new String[]{"all"});
        executor.throwAfterReentry = false;
        executor.onCommand(player(), command, "reenter", new String[]{"all"});

        assertEquals(2, executor.allBodies.get(), "a later dispatch by anyone runs: nothing was left held");
    }

    private static String pathKey(String methodName) {
        try {
            return ReenteringExecutor.class.getMethod(methodName, CommandSender.class).toString();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}

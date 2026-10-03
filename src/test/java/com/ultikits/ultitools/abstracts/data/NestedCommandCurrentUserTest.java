package com.ultikits.ultitools.abstracts.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;

/**
 * #541: a command body that dispatches another command inline keeps its own current user.
 * <p>
 * Since command bodies run at dispatch on the primary thread, a body that calls
 * {@code performCommand}/{@code dispatchCommand} runs the nested command's body on the same thread,
 * before its own body has finished. The audit user ({@link AuditableDataEntity}'s thread-local,
 * written into {@code created_by}/{@code updated_by}) is therefore saved before each body and
 * restored after it: the nested body sees its own sender, the outer body sees its own sender again
 * once the nested dispatch returns, and nothing is left on the thread after the outermost command.
 */
@DisplayName("A nested inline command keeps each body's own current user (#541)")
class NestedCommandCurrentUserTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    private Command command;

    /** The nested command: records the current user its body observes. */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"inner"})
    static class InnerExecutor extends BaseCommandExecutor {
        final AtomicReference<UUID> observed = new AtomicReference<>();
        final AtomicBoolean ran = new AtomicBoolean();
        boolean throwAfterObserving;

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void inner(CommandSender sender) {
            ran.set(true);
            observed.set(AuditableDataEntity.getCurrentUser());
            if (throwAfterObserving) {
                throw new IllegalStateException("nested body failed");
            }
        }
    }

    /** The outer command: dispatches the nested one inline, then records what it observes. */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"outer"})
    static class OuterExecutor extends BaseCommandExecutor {
        final InnerExecutor inner;
        final CommandSender nestedSender;
        final Command command;
        final AtomicReference<UUID> observedBefore = new AtomicReference<>();
        final AtomicReference<UUID> observedAfter = new AtomicReference<>();

        OuterExecutor(InnerExecutor inner, CommandSender nestedSender, Command command) {
            this.inner = inner;
            this.nestedSender = nestedSender;
            this.command = command;
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised.
        }

        @CmdMapping(format = "")
        public void outer(CommandSender sender) {
            observedBefore.set(AuditableDataEntity.getCurrentUser());
            // What Bukkit.dispatchCommand / Player#performCommand does on the primary thread.
            inner.onCommand(nestedSender, command, "inner", new String[]{});
            observedAfter.set(AuditableDataEntity.getCurrentUser());
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
        lenient().when(command.getName()).thenReturn("outer");
        AuditableDataEntity.clearCurrentUser();
    }

    @AfterEach
    void tearDown() {
        AuditableDataEntity.clearCurrentUser();
        bukkitMock.close();
        ultiToolsMock.close();
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("player A's body dispatching player B's command: B inside, A again after, nothing left")
    void nestedPlayerCommandRestoresTheOuterPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InnerExecutor inner = new InnerExecutor();
        OuterExecutor outer = new OuterExecutor(inner, player(b), command);

        outer.onCommand(player(a), command, "outer", new String[]{});

        assertEquals(a, outer.observedBefore.get());
        assertEquals(b, inner.observed.get(), "the nested body observes its own sender");
        assertEquals(a, outer.observedAfter.get(),
                "the outer body observes its own sender again after the nested dispatch returns");
        assertNull(AuditableDataEntity.getCurrentUser(), "no entry remains after the outermost command");
    }

    @Test
    @DisplayName("player A's body dispatching a console command: no user inside, A again after")
    void nestedConsoleCommandHasNoUserAndRestoresTheOuterPlayer() {
        UUID a = UUID.randomUUID();
        InnerExecutor inner = new InnerExecutor();
        OuterExecutor outer = new OuterExecutor(inner, mock(ConsoleCommandSender.class), command);

        outer.onCommand(player(a), command, "outer", new String[]{});

        assertTrue(inner.ran.get());
        assertNull(inner.observed.get(),
                "a console command's writes must not be attributed to the player whose body dispatched it");
        assertEquals(a, outer.observedAfter.get());
        assertNull(AuditableDataEntity.getCurrentUser());
    }

    @Test
    @DisplayName("a nested body that throws still restores the outer player")
    void throwingNestedBodyStillRestoresTheOuterPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InnerExecutor inner = new InnerExecutor();
        inner.throwAfterObserving = true;
        OuterExecutor outer = new OuterExecutor(inner, player(b), command);

        outer.onCommand(player(a), command, "outer", new String[]{});

        assertEquals(b, inner.observed.get());
        assertEquals(a, outer.observedAfter.get());
        assertNull(AuditableDataEntity.getCurrentUser());
    }

    @Test
    @DisplayName("a console body dispatching player B's command: B inside, no user after")
    void consoleOuterCommandEndsWithNoUser() {
        UUID b = UUID.randomUUID();
        InnerExecutor inner = new InnerExecutor();
        OuterExecutor outer = new OuterExecutor(inner, player(b), command);

        outer.onCommand(mock(ConsoleCommandSender.class), command, "outer", new String[]{});

        assertNull(outer.observedBefore.get());
        assertEquals(b, inner.observed.get());
        assertNull(outer.observedAfter.get(), "the console body has no user before or after");
        assertNull(AuditableDataEntity.getCurrentUser());
    }

    @Test
    @DisplayName("a command dispatched while the thread already carries a user leaves that user in place")
    void aUserSetBeforeTheCommandIsLeftInPlace() {
        UUID caller = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AuditableDataEntity.setCurrentUser(caller);
        InnerExecutor inner = new InnerExecutor();

        inner.onCommand(player(b), command, "inner", new String[]{});

        assertEquals(b, inner.observed.get());
        assertEquals(caller, AuditableDataEntity.getCurrentUser(),
                "code that set the user itself and then dispatched a command keeps its user");
    }
}

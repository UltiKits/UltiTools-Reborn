package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.catalogue;
import static com.ultikits.ultitools.utils.ModuleUpdateFixtures.moduleJar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.abstracts.command.CommandTestExtension;
import com.ultikits.ultitools.listeners.PendingChangesJoinListener;
import com.ultikits.ultitools.scheduling.TaskContext;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@ExtendWith(CommandTestExtension.class)
class PendingChangesJoinListenerTest {
    @TempDir File root;
    private ServerMock server;
    private ModuleFileTransactions transactions;
    private PendingChangesJoinListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        var owner = MockBukkit.createMockPlugin();
        TaskContext entity = CommandRuntime.scheduler().entity(mock(PlayerMock.class));
        when(entity.runLater(eq(Duration.ofMillis(50)), any(Runnable.class))).thenAnswer(invocation -> {
            server.getScheduler().runTaskLater(owner, invocation.getArgument(1, Runnable.class), 1);
            return null;
        });
        transactions = new ModuleFileTransactions(ModuleUpdateFixtures.dataFolderIn(root));
        listener = new PendingChangesJoinListener(transactions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void emptyListDoesNotMarkTheOperatorAndNonOperatorsNeverReceiveANotice() {
        var op = player(true);
        var guest = player(false);
        join(op);
        assertThat(op.nextMessage()).isNull();
        stage("1.0");
        join(guest);
        assertThat(guest.nextMessage()).isNull();
        join(op);
        assertThat(op.nextMessage()).contains("1 module change(s) are waiting for a restart");
    }

    @Test
    void onlyAdditionOrReplacementEnablesANewNoticeOnAJoin() {
        var op = player(true);
        stage("1.0");
        listener.onPlayerJoin(new PlayerJoinEvent(op, "joined"));
        assertThat(op.nextMessage()).isNull();
        server.getScheduler().performOneTick();
        assertThat(op.nextMessage()).contains("1 module change(s) are waiting for a restart");
        join(op);
        assertThat(op.nextMessage()).isNull();
        stage("1.1");
        server.getScheduler().performOneTick();
        assertThat(op.nextMessage()).isNull();
        join(op);
        assertThat(op.nextMessage()).contains("1 module change(s) are waiting for a restart");
        assertThat(op.nextMessage()).isNull();
        assertThat(new PendingChanges(transactions).revokeByModule("Demo").outcome())
                .isEqualTo(PendingChanges.RevokeResult.Outcome.REVOKED);
        join(op);
        assertThat(op.nextMessage()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void includesTheStartOutcomeOnFirstJoinButNotAfterANewPendingChange(boolean loaded) {
        stage("1.0");
        transactions.applyBeforeLoad();
        var jar = new File(ModuleFileTransactions.modulesFolder(ModuleUpdateFixtures.dataFolderIn(root)), "demo-1.0.jar");
        transactions.observeAfterLoad(loaded ? List.of(ModuleUpdateFixtures.loadedModule("Demo", "1.0", "demo"))
                : List.of(), ignored -> jar);
        var op = player(true);
        join(op);
        assertThat(op.nextMessage()).contains(loaded ? "1 module change(s) took effect"
                : "1 module change(s) were restored or need attention");
        stage("1.1");
        join(op);
        assertThat(op.nextMessage()).contains("1 module change(s) are waiting for a restart");
        assertThat(op.nextMessage()).isNull();
    }

    private PlayerMock player(boolean op) {
        var player = server.addPlayer();
        player.setOp(op);
        player.setLocale(Locale.ENGLISH);
        return player;
    }

    private void join(PlayerMock player) {
        listener.onPlayerJoin(new PlayerJoinEvent(player, "joined"));
        server.getScheduler().performOneTick();
    }

    private void stage(String version) {
        var result = transactions.stageChange("demo", version, "Demo", null, List::of, ignored -> null,
                catalogue("demo", version), (url, name, folder) -> moduleJar(new File(folder, name), "Demo", version, "demo"), null);
        assertThat(result.getOutcome()).isIn(ModuleFileTransactions.StageResult.Outcome.QUEUED_INSTALL,
                ModuleFileTransactions.StageResult.Outcome.REPLACED_INSTALL);
    }
}

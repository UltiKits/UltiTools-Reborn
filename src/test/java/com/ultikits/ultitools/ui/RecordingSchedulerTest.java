package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class RecordingSchedulerTest {
    private ManualScheduler.Engine clock;
    private RecordingScheduler scheduler;
    private ServerMock server;
    private PlayerMock viewer;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        viewer = server.addPlayer();
        clock = new ManualScheduler.Engine();
        scheduler = new RecordingScheduler(clock.scheduler("Recorder"));
    }

    @AfterEach
    void tearDown() {
        try {
            if (clock != null) {
                clock.close();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    @Test
    void entityTask_recordsEntityScope() {
        List<Optional<RecordingScheduler.Scope>> scopes = new ArrayList<>();
        TaskContext entity = scheduler.entity(viewer);
        TaskHandle once = entity.run(() -> scopes.add(RecordingScheduler.current()));
        entity.runLater(Duration.ZERO, () -> scopes.add(RecordingScheduler.current()));
        entity.runRepeating(Duration.ZERO, Duration.ofMillis(50), handle -> {
            scopes.add(RecordingScheduler.current());
            handle.cancel();
        });
        CompletableFuture<Boolean> supplied = entity.supply(() -> RecordingScheduler.inEntityTask(viewer));
        clock.advanceTicks(2);
        assertThat(scopes).containsExactly(entityScope(), entityScope(), entityScope());
        assertThat(supplied).isCompletedWithValue(true);
        assertThat(once.completion().toCompletableFuture()).isDone();
        assertThat(entity.isCurrentThread()).isTrue();
        assertThat(scheduler.owner()).isEqualTo("Recorder");
    }

    @Test
    void globalTask_recordsGlobalScope() {
        CompletableFuture<Boolean> supplied = scheduler.global().supply(RecordingScheduler::inGlobalTask);
        clock.advanceTicks(1);
        assertThat(supplied).isCompletedWithValue(true);
        assertThat(RecordingScheduler.current()).isEmpty();
    }

    @Test
    void regionAndAsyncTasks_recordTheirKind() {
        Location location = new Location(server.addSimpleWorld("recording"), 0, 64, 0);
        CompletableFuture<Optional<RecordingScheduler.Scope>> region = scheduler.region(location).supply(RecordingScheduler::current);
        CompletableFuture<Optional<RecordingScheduler.Scope>> chunk = scheduler.region(location.getWorld(), 1, 2)
                .supply(RecordingScheduler::current);
        CompletableFuture<Optional<RecordingScheduler.Scope>> async = scheduler.async().supply(RecordingScheduler::current);
        clock.advanceTicks(1);
        assertThat(region).isCompletedWithValue(scope(RecordingScheduler.Scope.Kind.REGION));
        assertThat(chunk).isCompletedWithValue(scope(RecordingScheduler.Scope.Kind.REGION));
        assertThat(async).isCompletedWithValue(scope(RecordingScheduler.Scope.Kind.ASYNC));
        assertThat(RecordingScheduler.current()).isEmpty();
    }

    @Test
    void outsideAnyTask_recordsNothing() {
        assertThat(RecordingScheduler.current()).isEmpty();
        assertThat(RecordingScheduler.inEntityTask(viewer)).isFalse();
        assertThat(RecordingScheduler.inGlobalTask()).isFalse();
    }

    @Test
    void execute_recordsScopeAndRestoresPrevious() {
        List<Optional<RecordingScheduler.Scope>> scopes = new ArrayList<>();
        scheduler.global().run(() -> {
            scopes.add(RecordingScheduler.current());
            scheduler.entity(viewer).execute(() -> scopes.add(RecordingScheduler.current()));
            scopes.add(RecordingScheduler.current());
        });
        TaskHandle failing = scheduler.entity(viewer).run(() -> {
            throw new IllegalStateException("scope must be restored even after failure");
        });
        clock.advanceTicks(2);
        assertThat(scopes).containsExactly(scope(RecordingScheduler.Scope.Kind.GLOBAL),
                scope(RecordingScheduler.Scope.Kind.GLOBAL), entityScope());
        assertThat(failing.completion().toCompletableFuture()).isCompletedExceptionally();
        assertThat(RecordingScheduler.current()).isEmpty();
    }

    private Optional<RecordingScheduler.Scope> entityScope() {
        return Optional.of(new RecordingScheduler.Scope(RecordingScheduler.Scope.Kind.ENTITY, viewer.getUniqueId()));
    }

    private static Optional<RecordingScheduler.Scope> scope(RecordingScheduler.Scope.Kind kind) {
        return Optional.of(new RecordingScheduler.Scope(kind, null));
    }
}

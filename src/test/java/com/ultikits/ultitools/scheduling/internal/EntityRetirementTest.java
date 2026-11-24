package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskRetiredException;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

@Timeout(30)
class EntityRetirementTest {
    private ServerMock server;
    private RetiringBackend backend;
    private OwnedScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        backend = new RetiringBackend(PaperBackend.live(plugin));
        scheduler = (OwnedScheduler) new PaperSchedulerEngine(plugin, Logger.getAnonymousLogger(), backend)
                .newOwner("retirement");
    }

    @AfterEach
    void tearDown() {
        try {
            if (scheduler != null) {
                scheduler.retire();
            }
        } finally {
            MockBukkitHelper.safeUnmock();
        }
    }

    @Test
    void entityRemovedInsideRepeatingBody_retiresWithoutAnotherDelivery() {
        Entity entity = server.addSimpleWorld("retirement").spawnEntity(
                server.getWorlds().get(0).getSpawnLocation(), EntityType.ARMOR_STAND);
        retireInsideBody(entity);
        assertThat(entity.isValid()).isFalse();
    }

    @Test
    void playerQuitsInsideRepeatingBody_retiresWithoutAnotherDelivery() {
        Entity player = server.addPlayer("retiring-player");
        retireInsideBody(player);
        assertThat(server.getPlayerExact("retiring-player")).isNull();
    }

    @Test
    void entityRemovedBetweenRuns_completesAtRemovalRatherThanNextDueTick() {
        Entity entity = server.addPlayer("between-runs");
        AtomicInteger runs = new AtomicInteger();
        TaskHandle task = scheduler.entity(entity).runRepeating(Duration.ZERO, Ticks.of(20),
                ignored -> runs.incrementAndGet());
        server.getScheduler().performTicks(1);
        assertThat(runs).hasValue(1);
        assertThat(task.state()).isEqualTo(TaskState.SCHEDULED);
        backend.retire(entity);
        assertRetired(task);
        server.getScheduler().performTicks(40);
        assertThat(runs).hasValue(1);
    }

    private void retireInsideBody(Entity entity) {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger bodyReturns = new AtomicInteger();
        TaskHandle task = scheduler.entity(entity).runRepeating(Duration.ZERO, Ticks.of(20), ignored -> {
            runs.incrementAndGet();
            backend.retire(entity);
            bodyReturns.incrementAndGet();
        });
        server.getScheduler().performTicks(1);
        assertRetired(task);
        assertThat(bodyReturns).hasValue(1);
        server.getScheduler().performTicks(40);
        assertThat(runs).hasValue(1);
    }

    private void assertRetired(TaskHandle task) {
        assertThat(task.state()).isEqualTo(TaskState.RETIRED);
        assertThat(task.completion().toCompletableFuture()).isCompletedExceptionally();
        assertThat(task.completion().toCompletableFuture())
                .hasFailedWithThrowableThat().isInstanceOf(TaskRetiredException.class);
        assertThat(scheduler.live).isEmpty();
    }
}

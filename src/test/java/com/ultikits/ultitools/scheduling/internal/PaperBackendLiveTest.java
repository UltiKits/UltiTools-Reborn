package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@Timeout(30)
class PaperBackendLiveTest {
    private ServerMock server;
    private PaperBackend backend;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        backend = PaperBackend.live(MockBukkit.createMockPlugin());
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void tickDeliveries_usePaperGlobalRegionAndEntitySchedulers() {
        World world = server.createWorld(new WorldCreator("backend"));
        PlayerMock player = server.addPlayer();
        List<String> events = new CopyOnWriteArrayList<>();
        ScheduledTask global = backend.global(task -> events.add("global"), 1);
        ScheduledTask region = backend.region(world, 2, -2, task -> events.add("region"), 2);
        ScheduledTask entity = backend.entity(player, task -> events.add("entity"), () -> events.add("retired"), 1);
        assertThat(List.of(global.getOwningPlugin(), region.getOwningPlugin(), entity.getOwningPlugin()))
                .containsOnly(server.getPluginManager().getPlugins()[0]);
        assertThat(events).isEmpty();
        server.getScheduler().performOneTick();
        assertThat(events).containsExactlyInAnyOrder("global", "entity");
        server.getScheduler().performOneTick();
        assertThat(events).containsExactlyInAnyOrder("global", "entity", "region");
        assertThat(backend.isOwnedByCurrentRegion(world, 2, -2)).isTrue();
        assertThat(backend.isOwnedByCurrentRegion(player)).isTrue();
    }

    @Test
    void asyncNowAndDelayed_runOffTickThread() throws InterruptedException {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        CountDownLatch executed = new CountDownLatch(2);
        backend.async(task -> {
            threads.add(Thread.currentThread());
            executed.countDown();
        }, 0);
        backend.async(task -> {
            threads.add(Thread.currentThread());
            executed.countDown();
        }, 100_000_000);
        server.getScheduler().performTicks(3);
        assertThat(executed.await(10, TimeUnit.SECONDS)).as("both async bodies executed").isTrue();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(threads).hasSize(2).doesNotContain(Thread.currentThread());
    }

    @Test
    void ownership_isTrueOnlyOnTickThread() throws Exception {
        AtomicReference<Boolean> other = new AtomicReference<>();
        Thread worker = new Thread(() -> other.set(backend.isGlobalTickThread()), "backend-ownership");
        worker.start();
        worker.join(10_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(backend.isGlobalTickThread()).isTrue();
        assertThat(other).hasValue(false);
    }

    @Test
    void disconnectProbe_reportsPlayerValidity_andRetiringBackendCoversAlreadyRemoved() {
        PlayerMock player = server.addPlayer();
        assertThat(player.isValid()).isTrue();
        player.disconnect();
        // V5: disconnect changes online state but EntityMock validity remains true in 4.123.0.
        assertThat(player.isOnline()).isFalse();
        assertThat(player.isValid()).isTrue();
        RetiringBackend retiring = new RetiringBackend(backend);
        retiring.retire(player);
        AtomicInteger runs = new AtomicInteger();
        assertThat(retiring.entity(player, task -> runs.incrementAndGet(), runs::incrementAndGet, 1)).isNull();
        server.getScheduler().performOneTick();
        assertThat(runs).hasValue(0);
    }
}

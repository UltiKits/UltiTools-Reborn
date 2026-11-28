package com.ultikits.ultitools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** MockBukkit 4.123.0 measurements, not a real Paper timing or scheduler implementation test. */
@Timeout(20)
class MockBukkitPaperSchedulerProbeTest {
    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void tickOwnership_isMainThreadNotTaskScope() throws Exception {
        World world = server.addSimpleWorld("probe-world");
        Player player = server.addPlayer();
        assertThat(Bukkit.isGlobalTickThread()).isTrue();
        assertThat(Bukkit.isOwnedByCurrentRegion(world, 0, 0)).isTrue();
        assertThat(Bukkit.isOwnedByCurrentRegion(player)).isTrue();
        FutureTask<List<Boolean>> work = new FutureTask<>(() -> List.of(Bukkit.isGlobalTickThread(),
                Bukkit.isOwnedByCurrentRegion(world, 0, 0), Bukkit.isOwnedByCurrentRegion(player)));
        Thread thread = new Thread(work, "mockbukkit-ownership-probe");
        thread.setDaemon(true);
        thread.start();
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(work.get()).containsExactly(false, false, false);
    }

    @Test
    void globalRun_isDeferredUntilOneTick() {
        AtomicInteger runs = new AtomicInteger();
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> runs.incrementAndGet());
        assertThat(runs).hasValue(0);
        server.getScheduler().performTicks(1);
        assertThat(runs).hasValue(1);
    }

    @Test
    void asyncRunNow_runsOffTickThread() throws InterruptedException {
        Thread tickThread = Thread.currentThread();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        List<Boolean> ownership = new CopyOnWriteArrayList<>();
        CountDownLatch ran = new CountDownLatch(1);
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            threads.add(Thread.currentThread());
            ownership.add(Bukkit.isGlobalTickThread());
            ran.countDown();
        });
        server.getScheduler().performOneTick();
        // waitAsyncTasksFinished() may return before a handed-off callback has even started.
        assertThat(ran.await(10, TimeUnit.SECONDS)).as("runNow callback ran after the tick").isTrue();
        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst()).isNotSameAs(tickThread);
        assertThat(ownership).containsExactly(false);
    }

    @Test
    void asyncDelay49Millis_runsAfterOneTick() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> {
            runs.incrementAndGet();
            ran.countDown();
        }, 49, TimeUnit.MILLISECONDS);
        server.getScheduler().performOneTick();
        assertThat(ran.await(10, TimeUnit.SECONDS)).as("49 ms callback ran after one tick").isTrue();
        assertThat(runs).hasValue(1);
    }
}

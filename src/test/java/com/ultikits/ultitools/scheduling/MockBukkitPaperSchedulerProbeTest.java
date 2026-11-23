package com.ultikits.ultitools.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void asyncRunNow_finishesOffTickThreadBeforeWaitReturns() {
        Thread tickThread = Thread.currentThread();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        List<Boolean> ownership = new CopyOnWriteArrayList<>();
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            threads.add(Thread.currentThread());
            ownership.add(Bukkit.isGlobalTickThread());
        });
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst()).isNotSameAs(tickThread);
        assertThat(ownership).containsExactly(false);
    }

    @Test
    void asyncDelay49Millis_isRoundedDownAndRunsAfterOneTick() {
        AtomicInteger runs = new AtomicInteger();
        Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> runs.incrementAndGet(), 49, TimeUnit.MILLISECONDS);
        server.getScheduler().performTicks(1);
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(runs).as("MockBukkit rounds 49 ms down to zero ticks").hasValue(1);
    }
}

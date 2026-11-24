package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Shared real-runtime fixture for all three unchanged abstract suites. */
final class RuntimeFixture implements AbstractUiServiceContractTest.Fixture {
    private final ManualScheduler.Engine clock = new ManualScheduler.Engine();
    private final RecordingScheduler scheduler = new RecordingScheduler(clock.scheduler("UiRuntime"));
    private final InMemoryContainerRuntime containers = new InMemoryContainerRuntime();
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();
    private final UiRuntime runtime;
    private final Map<String, UiService> services = new HashMap<>();

    RuntimeFixture() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) { warnings.add(record); }
            }
            @Override public void flush() { /* Records are captured synchronously. */ }
            @Override public void close() { /* No resources. */ }
        });
        runtime = new UiRuntime(scheduler, containers, logger, Thread.currentThread());
    }
    @Override public UiService service(String owner) { return services.computeIfAbsent(owner, runtime::service); }
    @Override public void register(UiBackend backend) { runtime.register(backend); }
    @Override public void advanceTicks(long ticks) {
        for (long tick = 0; tick < ticks; tick++) { clock.advanceTicks(1); }
    }
    @Override public void disconnect(PlayerMock player) {
        player.disconnect();
        runtime.quit(player);
        clock.retire(player);
    }
    @Override public void unload(String owner) { runtime.unload(services.get(owner)); }
    @Override public RecordingScheduler scheduler() { return scheduler; }
    @Override public GestureEngine engine() { return containers.engine(); }
    @Override public boolean gesture(GridSession session, ViewerItems side, Gesture gesture) {
        return runtime.applyGesture(session, side, gesture);
    }
    @Override public ItemStack shown(GridSession session, GridSlot slot) {
        return runtime.picture(session).getOrDefault(slot, ItemStack.empty()).clone();
    }
    @Override public List<LogRecord> warnings() { return List.copyOf(warnings); }
    @Override public void close() {
        try { runtime.close(); } finally { clock.close(); }
    }
}

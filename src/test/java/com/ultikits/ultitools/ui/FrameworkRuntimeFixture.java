package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.UiRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Real session and container runtimes; only the scheduler and screen backends are test probes. */
public final class FrameworkRuntimeFixture implements AbstractUiServiceContractTest.Fixture {
    private final ManualScheduler.Engine clock = new ManualScheduler.Engine();
    private final RecordingScheduler scheduler = new RecordingScheduler(clock.scheduler("FrameworkUi"));
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Map<String, UiService> services = new HashMap<>();
    private final FrameworkContainerRuntime containers;
    private final UiRuntime runtime;

    public FrameworkRuntimeFixture() {
        this(defaultContainers());
    }
    public FrameworkRuntimeFixture(FrameworkContainerRuntime containers) {
        this.containers = containers;
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }
            @Override public void flush() { /* Records are captured synchronously. */ }
            @Override public void close() { /* No external resources. */ }
        });
        runtime = new UiRuntime(scheduler, containers, logger, Thread.currentThread());
    }
    private static FrameworkContainerRuntime defaultContainers() {
        Thread thread = Thread.currentThread();
        InMemoryMessages messages = new InMemoryMessages("test",
                new FixedLocaleResolver(Locale.ENGLISH, Optional.empty()));
        return new FrameworkContainerRuntime(() -> thread.equals(Thread.currentThread()),
                (player, text) -> player.sendMessage(messages.render(text, player)));
    }
    @Override public UiService service(String owner) { return services.computeIfAbsent(owner, runtime::service); }
    /** Creates another module copy without the fixture's diagnostic-name cache. */
    public UiService anotherService(String owner) { return runtime.service(owner); }
    /** Unloads a particular module copy rather than its diagnostic name. */
    public void unload(UiService owner) { runtime.unload(owner); }
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

package com.ultikits.ultitools.scheduling.internal;

import com.ultikits.ultitools.scheduling.AbstractSchedulerContractTest;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.Ticks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

@Timeout(30)
class PaperSchedulerEngineContractTest extends AbstractSchedulerContractTest {
    @Override
    protected Fixture createFixture() {
        ServerMock server = MockBukkit.getMock();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
                // Records are captured synchronously in memory.
            }

            @Override
            public void close() {
                // No external resources are held.
            }
        });
        RetiringBackend backend = new RetiringBackend(PaperBackend.live(MockBukkit.createMockPlugin()));
        PaperSchedulerEngine engine = new PaperSchedulerEngine(server.getPluginManager().getPlugins()[0], logger, backend);
        Map<String, Scheduler> owners = new HashMap<>();
        return new Fixture() {
            @Override
            public Scheduler scheduler(String owner) {
                return owners.computeIfAbsent(owner, engine::newOwner);
            }

            @Override
            public void advanceTicks(long ticks) {
                server.getScheduler().performTicks(ticks);
            }

            @Override
            public void elapseAsync(Duration duration) {
                server.getScheduler().performTicks(Ticks.ceil(duration) + 1);
                server.getScheduler().waitAsyncTasksFinished();
            }

            @Override
            public void retire(Entity entity) {
                backend.retire(entity);
            }

            @Override
            public void unload(String owner) {
                engine.retire(owners.get(owner));
            }

            @Override
            public List<LogRecord> warnings() {
                return new ArrayList<>(warnings);
            }

            @Override
            public void close() {
                owners.values().forEach(engine::retire);
            }
        };
    }
}

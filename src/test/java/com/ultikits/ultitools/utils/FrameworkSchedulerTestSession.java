package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.internal.FrameworkScheduling;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Explicitly supplies the framework lifecycle normally installed by the root container. */
public final class FrameworkSchedulerTestSession implements AutoCloseable {
    private final PaperSchedulerEngine engine;
    private final Scheduler scheduler;

    public FrameworkSchedulerTestSession() {
        Plugin host = MockBukkit.createMockPlugin();
        engine = new PaperSchedulerEngine(host, host.getLogger(), PaperBackend.live(host));
        scheduler = engine.newOwner("UltiTools");
        FrameworkScheduling.install(scheduler);
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        try {
            engine.retire(scheduler);
        } finally {
            FrameworkScheduling.uninstall(scheduler);
        }
    }
}

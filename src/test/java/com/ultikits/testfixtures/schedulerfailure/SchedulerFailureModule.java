package com.ultikits.testfixtures.schedulerfailure;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.Ticks;
import java.util.concurrent.atomic.AtomicInteger;

/** A real JAR-backed module that schedules before activation and can fail during assembly. */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.schedulerfailure.empty")
public class SchedulerFailureModule extends UltiToolsPlugin {
    public static String failurePoint;
    public static SchedulerFailureModule constructed;
    public final AtomicInteger runs = new AtomicInteger();
    public TaskHandle pending;
    @Autowired public Scheduler scheduler;

    public SchedulerFailureModule() {
        remember(this);
    }

    private static void remember(SchedulerFailureModule module) {
        constructed = module;
    }

    @PostConstruct
    public void scheduleDuringAssembly() {
        pending = scheduler.global().runLater(Ticks.of(5), runs::incrementAndGet);
        if ("assembly".equals(failurePoint)) {
            throw new IllegalStateException("scheduler fixture assembly failure");
        }
    }

    @Override
    public boolean registerSelf() {
        if ("activation".equals(failurePoint)) {
            throw new IllegalStateException("scheduler fixture activation failure");
        }
        return false;
    }
}

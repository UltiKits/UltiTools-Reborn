package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.internal.PaperBackend;
import com.ultikits.ultitools.scheduling.internal.PaperSchedulerEngine;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class TaskManagerRetirementTest {
    @Test
    void cancelledModuleCannotObtainAFreshOwner_butSameNameReplacementCan() {
        TaskManager manager = manager();
        UltiToolsPlugin old = module();
        Scheduler retired = manager.schedulerFor(old);
        manager.cancelAll(old);
        manager.cancelAll(old);
        assertThatThrownBy(() -> manager.schedulerFor(old)).isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> retired.global().run(() -> { /* Must not execute. */ }))
                .isInstanceOf(RejectedExecutionException.class);
        Scheduler replacement = manager.schedulerFor(module());
        assertThat(replacement).isNotSameAs(retired);
        assertThat(replacement.global().run(() -> { /* Queued test work. */ }).state())
                .isEqualTo(TaskState.SCHEDULED);
    }

    @Test
    void cancellationContinuationCannotResurrectTheModuleDuringRetirement() {
        TaskManager manager = manager();
        UltiToolsPlugin module = module();
        TaskHandle pending = manager.schedulerFor(module).global().run(() -> { /* Queued test work. */ });
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        AtomicInteger continuations = new AtomicInteger();
        pending.completion().whenComplete((value, failure) -> {
            rejected.set(catchThrowable(() -> manager.schedulerFor(module)));
            continuations.incrementAndGet();
        });
        manager.cancelAll(module);
        assertThat(continuations).hasValue(1);
        assertThat(rejected.get()).isInstanceOf(RejectedExecutionException.class);
        assertThat(pending.state()).isEqualTo(TaskState.CANCELLED);
    }

    @Test
    void cancellationBeforeTheFirstSubmissionAlsoClosesTheModuleLifetime() {
        TaskManager manager = manager();
        UltiToolsPlugin module = module();
        manager.cancelAll(module);
        assertThatThrownBy(() -> manager.schedulerFor(module)).isInstanceOf(RejectedExecutionException.class);
    }

    private static TaskManager manager() {
        PaperBackend backend = mock(PaperBackend.class);
        when(backend.global(any(), anyLong())).thenReturn(mock(ScheduledTask.class));
        return new TaskManager(new PaperSchedulerEngine(mock(Plugin.class), Logger.getAnonymousLogger(), backend));
    }

    private static UltiToolsPlugin module() {
        UltiToolsPlugin module = mock(UltiToolsPlugin.class);
        when(module.getPluginName()).thenReturn("same-name");
        return module;
    }
}

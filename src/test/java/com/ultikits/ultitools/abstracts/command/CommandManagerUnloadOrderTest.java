package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.utils.TestHelper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkit;

@ExtendWith(CommandTestExtension.class)
class CommandManagerUnloadOrderTest {
    @AfterEach
    void cleanup() { MockBukkitHelper.safeUnmock(); }

    @Test
    void unregisterDrainsBeforeTasksCachesTheModuleHookAndItsContainer() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();
        var manager = new PluginManager();
        var tasks = mock(TaskManager.class);
        var caches = mock(PlayerCacheManager.class);
        ReflectionUtil.setFieldValue(manager, "taskManager", tasks);
        ReflectionUtil.setFieldValue(manager, "playerCacheManager", caches);
        var module = mock(UltiToolsPlugin.class);
        when(module.getPluginName()).thenReturn("UnloadFixture");
        var context = mock(SimpleContainer.class);
        when(module.getContext()).thenReturn(context);
        var executor = new CommandUnloadWaitTest.Fixture();
        when(context.getSingletonValues()).thenReturn(List.of(executor));
        List<String> order = new ArrayList<>();
        AtomicReference<Runnable> body = new AtomicReference<>();
        var async = CommandRuntime.scheduler().async();
        doAnswer(call -> {
            body.set(call.getArgument(0));
            return mock(TaskHandle.class);
        }).when(async).run(any(Runnable.class));
        doAnswer(call -> { order.add("tasks"); return null; }).when(tasks).cancelAll(module);
        doAnswer(call -> { order.add("cache"); return null; }).when(caches).unregisterBean(executor);
        doAnswer(call -> { order.add("unregister"); return null; }).when(module).unregisterSelf();
        doAnswer(call -> { order.add("context"); return null; }).when(context).close();
        AtomicLong clock = new AtomicLong();
        try (var timing = CommandRuntime.overrideWaitBudgets(() -> new CommandWaitBudget(Duration.ofSeconds(10),
                clock::get, (monitor, nanos) -> {
                    body.getAndSet(() -> { }).run();
                    order.add("body");
                }))) {
            assertThat(CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "probe", new String[] {"fast"}))
                    .isTrue();
            manager.unregister(module);
            assertThat(order).containsExactly("body", "tasks", "cache", "unregister", "context");
            assertThat(executor.finished.getCount()).isZero();
        } finally {
            if (body.get() != null) {
                body.get().run();
            }
        }
    }
}

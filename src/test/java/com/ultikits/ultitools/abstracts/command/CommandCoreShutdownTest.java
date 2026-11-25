package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.command.engine.CommandEngine;
import com.ultikits.ultitools.command.engine.CommandTestSupport;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.utils.TestHelper;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

@Timeout(15)
@ExtendWith(CommandTestExtension.class)
class CommandCoreShutdownTest {
    @AfterEach
    void cleanup() {
        PluginInitiationUtils.startAcceptingPanelMessages();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void registeredCoreBodyCompletesBeforeAnyShutdownServiceIsReleased() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        CommandEngine engine = mock(CommandEngine.class);
        CommandManager commands = new CommandManager(engine);
        var root = new SimpleContainer();
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(root);
        EventBus events = mock(EventBus.class);
        UltiTools plugin = TestHelper.mockUltiToolsInstance(core -> when(core.getCommandManager()).thenReturn(commands));
        ReflectionUtil.setFieldValue(plugin, "dependenceManagers", dependencies);
        ReflectionUtil.setFieldValue(plugin, "eventBus", events);
        ReflectionUtil.setFieldValue(plugin, "pluginManager", mock(PluginManager.class));
        doCallRealMethod().when(plugin).onDisable();
        ReflectionUtil.invoke(plugin, UltiTools.class.getDeclaredMethod("registerCommands"));
        var registrations = Mockito.mockingDetails(engine).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("register"))
                .map(call -> call.getArgument(2).getClass().getSimpleName()).toList();
        assertThat(registrations).containsExactly("UltiToolsCommands", "PluginInstallCommands", "CloudLoginCommand");
        CoreFixture executor = new CoreFixture();
        AtomicInteger completions = new AtomicInteger();
        executor.addValidator(new CommandValidator() {
            @Override public ValidationResult validate(CommandContext context) { return ValidationResult.success(); }
            @Override public void onComplete(CommandContext context, boolean success) { completions.incrementAndGet(); }
        });
        commands.registerCoreCommand(executor);
        assertThat(root.getSingletonValues()).doesNotContain(executor);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?>[] submitted = new Future<?>[1];
        when(CommandRuntime.scheduler().async().run(any(Runnable.class))).thenAnswer(call -> {
            submitted[0] = worker.submit(call.getArgument(0, Runnable.class));
            return mock(TaskHandle.class);
        });
        AtomicLong clock = new AtomicLong();
        AtomicInteger waits = new AtomicInteger();
        try (var timing = CommandRuntime.overrideWaitBudgets(() -> new CommandWaitBudget(Duration.ofSeconds(10),
                clock::get, (monitor, nanos) -> {
                    waits.incrementAndGet();
                    executor.release.countDown();
                    TimeUnit.SECONDS.timedWait(monitor, 5);
                }))) {
            assertThat(CommandTestSupport.dispatch(executor, mock(CommandSender.class), null, "coreprobe", new String[] {"slow"}))
                    .isTrue();
            assertThat(executor.started.await(5, TimeUnit.SECONDS)).isTrue();
            doAnswer(call -> {
                assertThat(completions.get()).isEqualTo(1);
                assertThat(executor.finished.getCount()).isZero();
                return null;
            }).when(events).shutdown();
            doAnswer(call -> {
                assertThat(completions.get()).isEqualTo(1);
                return null;
            }).when(dependencies).closeContext();
            plugin.onDisable();
            assertThat(waits.get()).isPositive();
            assertThat(completions.get()).isEqualTo(1);
        } finally {
            executor.release.countDown();
            if (submitted[0] != null) {
                submitted[0].get(5, TimeUnit.SECONDS);
            }
            worker.shutdownNow();
            root.close();
        }
    }

    @CmdExecutor(alias = "coreprobe")
    static class CoreFixture extends CommandUnloadWaitTest.Fixture { }
}

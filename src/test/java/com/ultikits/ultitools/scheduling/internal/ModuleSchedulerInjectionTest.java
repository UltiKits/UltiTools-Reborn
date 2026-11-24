package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.scheduling.Ticks;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

@Timeout(30)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ModuleSchedulerInjectionTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    public static class ConsumerBean {
        @Autowired Scheduler scheduler;
    }

    @UltiToolsModule(scanBasePackages = "com.ultikits.schedulerfixture.empty")
    public abstract static class Module extends UltiToolsPlugin {
        @Autowired Scheduler scheduler;
    }

    @Test
    void childTypeOverridesParent_withoutBecomingAnAnnotationScanSingleton() {
        PaperSchedulerEngine engine = new PaperSchedulerEngine(MockBukkit.createMockPlugin(),
                Logger.getAnonymousLogger(), mock(PaperBackend.class));
        Scheduler framework = engine.newOwner("UltiTools");
        Scheduler module = engine.newOwner("sample");
        SimpleContainer parent = new SimpleContainer();
        SimpleContainer child = new SimpleContainer();
        try {
            parent.registerType(Scheduler.class, framework);
            child.setParent(parent);
            child.registerType(Scheduler.class, module);
            ConsumerBean rootBean = new ConsumerBean();
            ConsumerBean moduleBean = new ConsumerBean();
            parent.registerSingleton("rootBean", rootBean);
            child.registerSingleton("moduleBean", moduleBean);
            assertThat(rootBean.scheduler).isSameAs(framework);
            assertThat(moduleBean.scheduler).isSameAs(module);
            assertThat(child.getSingletonValues()).containsExactly(moduleBean);
        } finally {
            child.close();
            parent.close();
        }
    }

    @Test
    void actualAssemblyAndUnregister_injectAndRetireExactModuleInstance() throws Exception {
        UltiTools host = mock(UltiTools.class);
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(host.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        when(host.getConfigManager()).thenReturn(mock(ConfigManager.class));
        DependenceManagers dependencies = mock(DependenceManagers.class);
        SimpleContainer parent = new SimpleContainer();
        JavaPlugin paperPlugin = MockBukkit.createMockPlugin();
        PaperSchedulerEngine engine = new PaperSchedulerEngine(paperPlugin,
                Logger.getAnonymousLogger(), PaperBackend.live(paperPlugin));
        Scheduler framework = engine.newOwner("UltiTools");
        parent.registerType(Scheduler.class, framework);
        when(dependencies.getContext()).thenReturn(parent);
        when(host.getDependenceManagers()).thenReturn(dependencies);
        try (MockedStatic<UltiTools> singleton = mockStatic(UltiTools.class)) {
            singleton.when(UltiTools::getInstance).thenReturn(host);
            singleton.when(UltiTools::getPluginVersion).thenReturn(700);
            PluginManager manager = new PluginManager();
            Field tasks = PluginManager.class.getDeclaredField("taskManager");
            tasks.setAccessible(true);
            tasks.set(manager, new TaskManager(paperPlugin));
            Module old = assemble(manager, "same-name");
            Module replacement = assemble(manager, "same-name");
            assertThat(old.scheduler.owner()).isEqualTo("same-name");
            assertThat(old.scheduler).isNotSameAs(framework).isNotSameAs(replacement.scheduler);
            AtomicInteger oldRuns = new AtomicInteger();
            AtomicInteger newRuns = new AtomicInteger();
            TaskHandle pending = old.scheduler.global().runLater(Ticks.of(5), oldRuns::incrementAndGet);
            TaskHandle surviving = replacement.scheduler.global().run(newRuns::incrementAndGet);
            manager.unregister(old);
            server.getScheduler().performTicks(6);
            assertThat(oldRuns).hasValue(0);
            assertThat(pending.state()).isEqualTo(TaskState.CANCELLED);
            assertThatThrownBy(() -> old.scheduler.global().run(oldRuns::incrementAndGet))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(newRuns).hasValue(1);
            assertThat(surviving.state()).isEqualTo(TaskState.COMPLETED);
            manager.unregister(replacement);
        } finally {
            parent.close();
        }
    }

    private Module assemble(PluginManager manager, String name) throws Exception {
        Module module = mock(Module.class);
        when(module.getPluginName()).thenReturn(name);
        when(module.getMainClass()).thenReturn(Module.class.getName());
        doCallRealMethod().when(module).setContext(any(SimpleContainer.class));
        when(module.getContext()).thenCallRealMethod();
        SimpleContainer child = new SimpleContainer();
        Method assemble = PluginManager.class.getDeclaredMethod("assemblePluginContainer", SimpleContainer.class,
                UltiToolsPlugin.class, Class.class, ClassLoader.class);
        assemble.setAccessible(true);
        assemble.invoke(manager, child, module, Module.class, getClass().getClassLoader());
        return module;
    }
}

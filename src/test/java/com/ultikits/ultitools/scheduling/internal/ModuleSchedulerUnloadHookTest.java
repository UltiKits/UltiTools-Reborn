package com.ultikits.ultitools.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(30)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ModuleSchedulerUnloadHookTest {
    private SimpleContainer parent;
    private MockedStatic<UltiTools> singleton;
    private PluginManager manager;

    @UltiToolsModule(scanBasePackages = "com.ultikits.schedulerfixture.empty")
    public abstract static class HookModule extends UltiToolsPlugin {
        @Autowired Scheduler scheduler;
        boolean submitOnUnload;
        int unloadCalls;

        @Override
        public void onUnregister() {
            unloadCalls++;
            if (submitOnUnload) {
                scheduler.global().run(() -> { /* A retired owner must reject this work. */ });
            }
        }
    }

    public static class DestroyProbe {
        @Autowired Scheduler scheduler;
        int attempts;
        RejectedExecutionException rejection;

        @PreDestroy
        public void close() {
            attempts++;
            try {
                scheduler.global().run(() -> { /* A retired owner must reject this work. */ });
            } catch (RejectedExecutionException failure) {
                rejection = failure;
                throw failure;
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        UltiTools host = mock(UltiTools.class);
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(host.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        when(host.getConfigManager()).thenReturn(mock(ConfigManager.class));
        DependenceManagers dependencies = mock(DependenceManagers.class);
        parent = new SimpleContainer();
        when(dependencies.getContext()).thenReturn(parent);
        when(host.getDependenceManagers()).thenReturn(dependencies);
        singleton = mockStatic(UltiTools.class);
        singleton.when(UltiTools::getInstance).thenReturn(host);
        manager = new PluginManager();
        Field field = PluginManager.class.getDeclaredField("taskManager");
        field.setAccessible(true);
        field.set(manager, new TaskManager(MockBukkit.createMockPlugin()));
    }

    @AfterEach
    void tearDown() {
        try {
            parent.close();
        } finally {
            if (singleton != null) {
                singleton.close();
            }
            MockBukkitHelper.safeUnmock();
        }
    }

    @Test
    void onUnregisterSubmission_isSurfacedAndStillClosesTheContainer() throws Exception {
        HookModule module = assemble();
        DestroyProbe destroy = new DestroyProbe();
        module.getContext().registerSingleton("destroyProbe", destroy);
        module.submitOnUnload = true;
        assertThatThrownBy(() -> manager.unregister(module)).isInstanceOf(RejectedExecutionException.class);
        assertThat(module.unloadCalls).isEqualTo(1);
        assertThat(destroy.attempts).isEqualTo(1);
        assertThat(destroy.rejection).isInstanceOf(RejectedExecutionException.class);
        assertThat(module.getContext().getSingletonValues()).isEmpty();
        assertThat(manager.getPluginList()).doesNotContain(module);
    }

    @Test
    void preDestroySubmission_isRejectedAndDoesNotPreventContainerClose() throws Exception {
        HookModule module = assemble();
        DestroyProbe destroy = new DestroyProbe();
        module.getContext().registerSingleton("destroyProbe", destroy);
        manager.unregister(module);
        assertThat(destroy.attempts).isEqualTo(1);
        assertThat(destroy.rejection).isInstanceOf(RejectedExecutionException.class);
        assertThat(module.unloadCalls).isEqualTo(1);
        assertThat(module.getContext().getSingletonValues()).isEmpty();
        assertThat(manager.getPluginList()).doesNotContain(module);
    }

    private HookModule assemble() throws Exception {
        HookModule module = mock(HookModule.class);
        when(module.getPluginName()).thenReturn("unload-hooks");
        when(module.getMainClass()).thenReturn(HookModule.class.getName());
        when(module.getCommandManager()).thenReturn(mock(CommandManager.class));
        when(module.getListenerManager()).thenReturn(mock(ListenerManager.class));
        doCallRealMethod().when(module).setContext(any(SimpleContainer.class));
        when(module.getContext()).thenCallRealMethod();
        doCallRealMethod().when(module).unregisterSelf();
        doCallRealMethod().when(module).onUnregister();
        Method assemble = PluginManager.class.getDeclaredMethod("assemblePluginContainer", SimpleContainer.class,
                UltiToolsPlugin.class, Class.class, ClassLoader.class);
        assemble.setAccessible(true);
        assemble.invoke(manager, new SimpleContainer(), module, HookModule.class, getClass().getClassLoader());
        Method registered = PluginManager.class.getDeclaredMethod("onPluginRegistered", UltiToolsPlugin.class);
        registered.setAccessible(true);
        registered.invoke(manager, module);
        return module;
    }
}

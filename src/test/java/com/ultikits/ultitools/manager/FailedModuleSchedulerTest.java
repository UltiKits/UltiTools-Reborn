package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.ultikits.testfixtures.schedulerfailure.SchedulerFailureModule;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Exercises all four pre-listing failure exits through the public registration entry points. */
@Timeout(30)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class FailedModuleSchedulerTest {
    @TempDir Path tempDir;
    private ServerMock server;
    private SimpleContainer parent;
    private PluginManager manager;
    private TaskManager tasks;
    private MockedStatic<UltiTools> singleton;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        parent = new SimpleContainer();
        DependenceManagers dependencies = mock(DependenceManagers.class);
        when(dependencies.getContext()).thenReturn(parent);
        UltiTools host = mock(UltiTools.class);
        when(host.getDataFolder()).thenReturn(tempDir.toFile());
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(host.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        when(host.getDependenceManagers()).thenReturn(dependencies);
        when(host.getConfigManager()).thenReturn(mock(ConfigManager.class));
        when(host.getCommandManager()).thenReturn(mock(CommandManager.class));
        when(host.getListenerManager()).thenReturn(mock(ListenerManager.class));
        when(host.getConfig()).thenReturn(new YamlConfiguration());
        singleton = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        singleton.when(UltiTools::getInstance).thenReturn(host);
        singleton.when(UltiTools::getPluginVersion).thenReturn(700);
        manager = new PluginManager();
        tasks = new TaskManager(MockBukkit.createMockPlugin());
        setManagerField("taskManager", tasks);
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
    void registerSelfReturningFalse_retiresPreActivationWork() throws Exception {
        assertFailedLoad("refused", false);
    }

    @Test
    void registerSelfThrowing_retiresPreActivationWork() throws Exception {
        assertFailedLoad("activation", false);
    }

    @Test
    void instanceAssemblyThrowing_retiresPreActivationWork() throws Exception {
        assertFailedLoad("assembly", true);
    }

    @Test
    void classAssemblyThrowing_retiresPreActivationWork() throws Exception {
        assertFailedLoad("assembly", false);
    }

    private void assertFailedLoad(String failurePoint, boolean suppliedInstance) throws Exception {
        try (PluginManagerFirstAssemblyTest.FixtureJarClassLoader loader =
                     new PluginManagerFirstAssemblyTest.FixtureJarClassLoader(buildModuleJar().toFile(),
                             getClass().getClassLoader(), "com.ultikits.testfixtures.schedulerfailure.")) {
            Class<? extends UltiToolsPlugin> moduleClass = Class.forName(
                    SchedulerFailureModule.class.getName(), true, loader).asSubclass(UltiToolsPlugin.class);
            moduleClass.getField("failurePoint").set(null, failurePoint);
            setManagerField("classLoader", loader);
            boolean registered = suppliedInstance
                    ? manager.register(moduleClass.getDeclaredConstructor().newInstance())
                    : manager.register(moduleClass);
            assertThat(registered).isFalse();
            UltiToolsPlugin module = (UltiToolsPlugin) moduleClass.getField("constructed").get(null);
            assertThat(module).isNotNull();
            TaskHandle pending = (TaskHandle) moduleClass.getField("pending").get(module);
            assertThat(pending).as("the actual @PostConstruct must have submitted work before failing").isNotNull();
            AtomicInteger runs = (AtomicInteger) moduleClass.getField("runs").get(module);
            assertThat(manager.getPluginList()).doesNotContain(module);
            Field mappings = TaskManager.class.getDeclaredField("moduleSchedulers");
            mappings.setAccessible(true);
            assertThat((Map<?, ?>) mappings.get(tasks)).isEmpty();
            assertThat(pending.state()).isEqualTo(TaskState.CANCELLED);
            assertThat(pending.completion().toCompletableFuture()).isCompletedExceptionally()
                    .hasFailedWithThrowableThat().isInstanceOf(CancellationException.class);
            Scheduler scheduler = (Scheduler) moduleClass.getField("scheduler").get(module);
            assertThatThrownBy(() -> scheduler.global().run(runs::incrementAndGet))
                    .isInstanceOf(RejectedExecutionException.class);
            server.getScheduler().performTicks(10);
            assertThat(runs).hasValue(0);
        }
    }

    private Path buildModuleJar() throws Exception {
        Path jar = tempDir.resolve("scheduler-failure.jar");
        String resource = SchedulerFailureModule.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resource));
            try (InputStream input = getClass().getResourceAsStream("/" + resource)) {
                assertThat(input).isNotNull();
                input.transferTo(output);
            }
            output.closeEntry();
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: SchedulerLoadFailure\nversion: 1.0.0\napi-version: 700\nmain: "
                    + SchedulerFailureModule.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private void setManagerField(String name, Object value) throws Exception {
        Field field = PluginManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}

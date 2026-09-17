package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.manager.TaskManager;

/**
 * {@link PluginInstallUtils#uninstallPlugin(String)} -- the method behind {@code /upm uninstall}.
 * <p>
 * #503: the method unloaded a module with {@code plugin.unregisterSelf()} alone, bypassing
 * {@link PluginManager#unregister(UltiToolsPlugin)}, the framework's one full unload path. Every
 * registry cleanup that lives only there -- cancelling the module's {@code @Scheduled} tasks first
 * among them -- was skipped, so an "uninstalled" module's repeating task kept firing until restart.
 * The test below drives a real {@link TaskManager} on MockBukkit's scheduler and asserts the
 * observable effect (the task stops running), not merely that some method was called.
 * <p>
 * #501: the method ignored {@code File#delete()}'s result and returned {@code true} whether or not
 * the jar was removed, so the command could not report a failed delete. A delete that fails must
 * now surface as a {@link FileSystemException} naming the jar that is still on disk.
 */
@DisplayName("PluginInstallUtils#uninstallPlugin (#503 single unload path, #501 honest delete result)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsUninstallTest {

    private static final String MODULE_NAME = "UninstallFixture";

    @TempDir
    File dataFolder;

    private ServerMock server;
    private PluginManager pluginManager;
    private File pluginsFolder;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        pluginsFolder = new File(dataFolder, "plugins");
        assertThat(pluginsFolder.mkdirs()).isTrue();

        AtomicReference<PluginManager> pluginManagerRef = new AtomicReference<>();
        CommandManager commandManager = mock(CommandManager.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenAnswer(invocation -> pluginManagerRef.get());
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
        });
        pluginManager = new PluginManager();
        pluginManagerRef.set(pluginManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    /** A module bean carrying one repeating {@code @Scheduled} task that counts its own runs. */
    public static class TickingBean {
        private final AtomicInteger runs = new AtomicInteger();

        @Scheduled(period = 1)
        public void tick() {
            runs.incrementAndGet();
        }
    }

    @Test
    @DisplayName("#503: uninstalling a loaded module cancels its @Scheduled task, removes it from the plugin list and deletes its jar")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // injecting a real TaskManager bound to a MockBukkit host plugin
    void uninstallLoadedModule_stopsItsScheduledTask() throws Exception {
        PluginMock host = MockBukkit.createMockPlugin("UninstallHost");
        TaskManager taskManager = new TaskManager(host);
        Field taskManagerField = PluginManager.class.getDeclaredField("taskManager");
        taskManagerField.setAccessible(true);
        taskManagerField.set(pluginManager, taskManager);

        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.getPluginName()).thenReturn(MODULE_NAME);
        doCallRealMethod().when(plugin).unregisterSelf();
        pluginManager.getPluginList().add(plugin);

        TickingBean bean = new TickingBean();
        taskManager.registerScheduledMethods(plugin, bean);
        File jar = writeModuleJar(MODULE_NAME);

        server.getScheduler().performTicks(5);
        int runsBeforeUninstall = bean.runs.get();
        assertThat(runsBeforeUninstall)
                .as("control: the fixture task must actually be running before the uninstall, "
                        + "otherwise 'it stopped' below would pass vacuously")
                .isPositive();

        assertThat(PluginInstallUtils.uninstallPlugin(MODULE_NAME)).isTrue();

        server.getScheduler().performTicks(10);
        assertThat(bean.runs.get())
                .as("an uninstalled module's @Scheduled task must stop running -- it is cancelled "
                        + "only by PluginManager#unregister, which uninstallPlugin must go through (#503)")
                .isEqualTo(runsBeforeUninstall);
        assertThat(pluginManager.getPluginList()).doesNotContain(plugin);
        assertThat(jar).doesNotExist();
    }

    @Test
    @DisplayName("#501: a jar that cannot be deleted is reported as a FileSystemException naming that jar, never as success")
    void undeletableJar_isReportedAsFailureNamingTheJar() throws Exception {
        Path folder = pluginsFolder.toPath();
        Assumptions.assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"),
                "needs POSIX permissions to make the delete fail");
        File jar = writeModuleJar(MODULE_NAME);

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(folder);
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(folder),
                    "running as a user that can write a read-only directory (e.g. root); the delete cannot be made to fail");

            Throwable thrown = catchThrowable(() -> PluginInstallUtils.uninstallPlugin(MODULE_NAME));

            assertThat(thrown)
                    .as("a failed delete must not be reported as a successful uninstall (#501)")
                    .isInstanceOf(FileSystemException.class);
            assertThat(((FileSystemException) thrown).getFile()).isEqualTo(jar.getAbsolutePath());
            assertThat(jar).exists();
        } finally {
            Files.setPosixFilePermissions(folder, original);
        }
    }

    private File writeModuleJar(String moduleName) throws IOException {
        File jar = new File(pluginsFolder, moduleName + "-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: " + moduleName + "\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}

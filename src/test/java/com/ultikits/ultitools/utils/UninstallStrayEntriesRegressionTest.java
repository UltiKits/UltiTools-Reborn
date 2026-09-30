package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Regression check for #504 (fixed on {@code alpha} by {@code 2fef89e6}, closed on evidence by plan
 * 17-39): {@code /upm uninstall} skips a subdirectory, a non-JAR file and a {@code .jar.bak} copy,
 * reports a JAR without a {@code plugin.yml} rather than aborting on it, closes every archive it
 * opens, and works on a server path containing a space. Not a fix of this plan: it guards the
 * module-identity changes of #516 against undoing that fix.
 */
@DisplayName("/upm uninstall with stray entries and a space in the server path (#504 regression)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UninstallStrayEntriesRegressionTest {

    @TempDir
    File tempDir;

    private File modules;

    @BeforeEach
    void setUp() {
        File dataFolder = ModuleUpdateFixtures.dataFolderIn(new File(tempDir, "server root"));
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();
        PluginManager pluginManager = new PluginManager();
        CommandManager commandManager = mock(CommandManager.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private File jar(String name, String pluginYml) throws IOException {
        File jar = new File(modules, name);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            if (pluginYml != null) {
                out.putNextEntry(new JarEntry("plugin.yml"));
                out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            } else {
                out.putNextEntry(new JarEntry("com/example/Nothing.class"));
                out.write(new byte[]{(byte) 0xCA, (byte) 0xFE});
                out.closeEntry();
            }
        }
        return jar;
    }

    /** The files under the modules folder this process holds open, where the platform can say. */
    private List<String> openFilesUnderModules() throws IOException {
        List<String> open = new ArrayList<>();
        Path fds = Paths.get("/proc/self/fd");
        if (!Files.isDirectory(fds)) {
            return open;
        }
        String prefix = modules.getCanonicalPath();
        try (Stream<Path> entries = Files.list(fds)) {
            entries.forEach(fd -> {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith(prefix)) {
                        open.add(target);
                    }
                } catch (IOException | UnsupportedOperationException gone) {
                    // the descriptor closed while listing
                }
            });
        }
        return open;
    }

    @Test
    @DisplayName("#504: stray entries are skipped, the module's JAR is deleted, every archive is closed")
    void strayEntries_areSkipped_andTheModuleJarIsDeleted() throws IOException {
        assertThat(modules.getAbsolutePath()).contains("server root");
        File module = jar("Fixture-1.0.jar", "name: Fixture\nmain: com.example.Fixture\n");
        File subfolder = new File(modules, "config");
        assertThat(subfolder.mkdirs()).isTrue();
        File notes = new File(modules, "notes.txt");
        Files.write(notes.toPath(), "notes".getBytes(StandardCharsets.UTF_8));
        File backup = jar("old.jar.bak", "name: Fixture\nmain: com.example.Fixture\n");
        File noPluginYml = jar("library.jar", null);
        // Control: the open-file check does see a file held open under the modules folder.
        try (java.io.InputStream held = Files.newInputStream(notes.toPath())) {
            org.junit.jupiter.api.Assumptions.assumeTrue(held != null && !openFilesUnderModules().isEmpty(),
                    "the platform does not list open files (/proc/self/fd)");
        }

        PluginInstallUtils.UninstallReport report = PluginInstallUtils.uninstallPluginReporting("Fixture");

        assertThat(report.jarsDeleted()).isTrue();
        assertThat(module).doesNotExist();
        assertThat(report.deletedFiles()).containsExactly(module.getAbsolutePath());
        assertThat(subfolder).isDirectory();
        assertThat(notes).exists();
        assertThat(backup).as("not a .jar: the loader never loads it").exists();
        assertThat(noPluginYml).exists();
        assertThat(report.undeterminedEntries()).containsExactly(noPluginYml.getAbsolutePath());
        assertThat(openFilesUnderModules()).isEmpty();
    }
}

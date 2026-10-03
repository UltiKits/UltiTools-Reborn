package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginListSeeding;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * A second copy of a module whose {@code plugin.yml} declares a different {@code name:} than the
 * loaded copy's JAR, but the same {@code main:}, is found and deleted by {@code /upm uninstall}
 * (#516). The module loader identifies a module JAR by nothing but its {@code plugin.yml}
 * {@code main:} (#548/#549) and records, per main class, every JAR that declares it; the uninstall
 * matches on the same declaration, so what it deletes and what the loader would load again are
 * decided by one rule. No class is read out of any archive.
 */
@DisplayName("/upm uninstall deletes every JAR that declares the module's main class (#516)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleJarIndexUninstallTest {

    private static final String MAIN = IndexFixtureModule.class.getName();
    private static final String OTHER_MAIN = OtherFixtureModule.class.getName();

    @TempDir
    File serverRoot;

    private File modules;
    private PluginManager pluginManager;

    /** The loaded module's class; a mock of it is a subclass, as a proxy would be. */
    static class IndexFixtureModule extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    /** Another loaded module's class. */
    static class OtherFixtureModule extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        File dataFolder = ModuleUpdateFixtures.dataFolderIn(serverRoot);
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        modules = ModuleFileTransactions.modulesFolder(dataFolder);
        assertThat(modules.mkdirs()).isTrue();
        AtomicReference<PluginManager> ref = new AtomicReference<>();
        CommandManager commandManager = mock(CommandManager.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getDataFolder()).thenReturn(dataFolder);
            when(ultiTools.getPluginManager()).thenAnswer(invocation -> ref.get());
            when(ultiTools.getCommandManager()).thenReturn(commandManager);
            when(ultiTools.getListenerManager()).thenReturn(listenerManager);
        });
        pluginManager = new PluginManager();
        ref.set(pluginManager);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    private File jar(String fileName, String pluginYml) throws IOException {
        File jar = new File(modules, fileName);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /** A loaded instance of {@code type}, with the runtime name given. */
    private <T extends UltiToolsPlugin> T loaded(Class<T> type, String runtimeName) {
        T plugin = mock(type);
        when(plugin.getPluginName()).thenReturn(runtimeName);
        doCallRealMethod().when(plugin).unregisterSelf();
        PluginListSeeding.add(pluginManager, plugin);
        return plugin;
    }

    /** What the loader's start-up read records for each JAR: its declared main class. */
    private void loaderRead(String mainClass, File... jars) {
        for (File jar : jars) {
            pluginManager.getModuleJarIndex().record(mainClass, jar);
        }
    }

    @Test
    @DisplayName("#516: a copy declaring another name: but the same main: is deleted, and both files are named")
    void copyWithAnotherNameAndTheSameMain_isDeletedAndNamed() throws IOException {
        File a = jar("A.jar", "name: Fixture\nmain: " + MAIN + "\n");
        File b = jar("B.jar", "name: RenamedFixture\nmain: " + MAIN + "\n");
        UltiToolsPlugin plugin = loaded(IndexFixtureModule.class, "Fixture");
        loaderRead(MAIN, a, b);

        PluginInstallUtils.UninstallReport report = PluginInstallUtils.uninstallPluginReporting("Fixture",
                module -> module == plugin ? a : null);

        assertThat(a).doesNotExist();
        assertThat(b).as("it declares the loaded module's main class: after a restart it loads that module again")
                .doesNotExist();
        assertThat(report.jarsDeleted()).isTrue();
        assertThat(report.deletedFiles()).containsExactly(a.getAbsolutePath(), b.getAbsolutePath());
        assertThat(report.undeterminedEntries()).isEmpty();
    }

    @Test
    @DisplayName("#516: a copy declaring the same main: and no name: at all is deleted, not left undetermined")
    void copyWithTheSameMainAndNoName_isDeleted() throws IOException {
        File a = jar("A.jar", "name: Fixture\nmain: " + MAIN + "\n");
        File b = jar("B.jar", "main: " + MAIN + "\n");
        UltiToolsPlugin plugin = loaded(IndexFixtureModule.class, "Fixture");
        loaderRead(MAIN, a, b);

        PluginInstallUtils.UninstallReport report = PluginInstallUtils.uninstallPluginReporting("Fixture",
                module -> module == plugin ? a : null);

        assertThat(b).doesNotExist();
        assertThat(report.deletedFiles()).containsExactly(a.getAbsolutePath(), b.getAbsolutePath());
        assertThat(report.undeterminedEntries()).isEmpty();
    }

    @Test
    @DisplayName("#516 T-17-44-01: a JAR sharing the name prefix but declaring another main: is never deleted")
    void jarSharingTheNamePrefixButNotTheMain_isKept() throws IOException {
        File a = jar("A.jar", "name: Fixture\nmain: " + MAIN + "\n");
        File prefixed = jar("A-extra.jar", "name: FixtureExtra\nmain: com.example.FixtureExtra\n");
        UltiToolsPlugin plugin = loaded(IndexFixtureModule.class, "Fixture");
        loaderRead(MAIN, a);
        loaderRead("com.example.FixtureExtra", prefixed);

        PluginInstallUtils.UninstallReport report = PluginInstallUtils.uninstallPluginReporting("Fixture",
                module -> module == plugin ? a : null);

        assertThat(a).doesNotExist();
        assertThat(prefixed).exists();
        assertThat(report.deletedFiles()).containsExactly(a.getAbsolutePath());
    }

    @Test
    @DisplayName("#516: a JAR declaring another loaded module's main class is never taken by this uninstall")
    void jarDeclaringAnotherLoadedModulesMain_isKept() throws IOException {
        File a = jar("A.jar", "name: Fixture\nmain: " + MAIN + "\n");
        File other = jar("O.jar", "name: Other\nmain: " + OTHER_MAIN + "\n");
        UltiToolsPlugin plugin = loaded(IndexFixtureModule.class, "Fixture");
        UltiToolsPlugin bystander = loaded(OtherFixtureModule.class, "Other");
        loaderRead(MAIN, a);
        loaderRead(OTHER_MAIN, other);

        PluginInstallUtils.uninstallPluginReporting("Fixture",
                module -> module == plugin ? a : module == bystander ? other : null);

        assertThat(a).doesNotExist();
        assertThat(other).exists();
        assertThat(pluginManager.getPluginList()).containsExactly(bystander);
    }

    @Test
    @DisplayName("#516: a file the loader read as this module's that now declares something else is not deleted")
    void indexedJarReplacedSinceTheStart_isNotDeleted() throws IOException {
        File a = jar("A.jar", "name: Fixture\nmain: " + MAIN + "\n");
        File b = jar("B.jar", "name: Fixture2\nmain: " + MAIN + "\n");
        UltiToolsPlugin plugin = loaded(IndexFixtureModule.class, "Fixture");
        loaderRead(MAIN, a, b);
        // Replaced after the start with a JAR of an unrelated module under the same name.
        jar("B.jar", "name: Unrelated\nmain: com.example.Unrelated\n");

        PluginInstallUtils.UninstallReport report = PluginInstallUtils.uninstallPluginReporting("Fixture",
                module -> module == plugin ? a : null);

        assertThat(b).exists();
        assertThat(report.deletedFiles()).containsExactly(a.getAbsolutePath());
    }
}

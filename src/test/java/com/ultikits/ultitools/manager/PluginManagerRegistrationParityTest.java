package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;

/**
 * WIRE-05/WIRE-06 (#203/#326): {@code PluginManager.register(UltiToolsPlugin)} and {@code
 * PluginManager.initializePlugin} now build their container through one shared assembly method,
 * so a capability added to one entry point can no longer be silently missing from the other.
 * <p>
 * Reuses the jar-backed-{@code CodeSource} classloader trick {@code
 * RegisterSingletonAssemblyTest.InitializePluginOrdering} and {@code
 * PluginManagerRegisterInstanceOrderingTest} both already established for {@code
 * UltiToolsPlugin}'s no-arg constructor, and {@link
 * com.ultikits.testfixtures.registrationparity.ParityFixtureModule}'s combined fixture -- see
 * that package's javadoc -- to assemble the SAME plugin class through both entry points and
 * compare the resulting containers directly.
 */
@DisplayName("PluginManager registration entry-point parity (WIRE-05/WIRE-06)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerRegistrationParityTest {

    private static final String FIXTURE_PACKAGE = "com.ultikits.testfixtures.registrationparity.";

    @TempDir
    File tempDir;

    @BeforeEach
    void setUpBukkit() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        org.mockbukkit.mockbukkit.MockBukkit.mock();
    }

    @AfterEach
    void tearDownBukkit() {
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /**
     * Child-first for the fixture package only -- identical rationale to the copy in {@code
     * RegisterSingletonAssemblyTest.InitializePluginOrdering} and {@code
     * PluginManagerRegisterInstanceOrderingTest}: {@code UltiToolsPlugin}'s no-arg constructor
     * needs the plugin class's own {@code CodeSource} to be a real jar file, which {@code
     * target/test-classes} is not. A class in this package NOT present in the jar (e.g. a
     * {@code @ContextEntry} target class not bundled alongside its module) falls through to
     * normal parent-first delegation, which still finds it on the ambient test classpath.
     */
    private final class FixtureJarClassLoader extends URLClassLoader {
        private final String isolatedPrefix;

        FixtureJarClassLoader(URL jarUrl, ClassLoader parent, String isolatedPrefix) {
            super(new URL[]{jarUrl}, parent);
            this.isolatedPrefix = isolatedPrefix;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && name.startsWith(isolatedPrefix)) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Not present in this loader's own jar -- fall through to normal
                        // parent-first delegation below.
                    }
                }
                if (found == null) {
                    found = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }

    private File buildFixtureJar(String jarName, String pluginYmlName, Class<?>... classes) throws Exception {
        File jar = new File(tempDir, jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Class<?> clazz : classes) {
                writeClassEntry(output, clazz);
            }
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: " + pluginYmlName + "\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private void writeClassEntry(JarOutputStream output, Class<?> clazz) throws Exception {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resourceName));
        try (InputStream input = clazz.getResourceAsStream("/" + resourceName)) {
            assertNotNull(input, "compiled class resource for " + clazz.getName());
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }

    /**
     * Builds the mocked {@code UltiTools} singleton every assembly path reads through --
     * {@code getDataFolder}, {@code getLogger}, {@code getDataStore}, {@code
     * getDependenceManagers}, {@code getConfigManager} and {@code getConfig} -- mirroring {@code
     * PluginManagerRegisterInstanceOrderingTest}'s setup exactly. {@code configManager} is passed
     * in rather than always freshly mocked, so a test that needs {@code getAllConfigEntities}
     * stubbed can supply its own.
     */
    private UltiTools newMockUltiTools(ConfigManager configManager) {
        DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
        when(mockDependenceManagers.getContext()).thenReturn(
                new com.ultikits.ultitools.context.SimpleContainer());

        // wireAop (called by the shared assembly before refresh()) resolves a DataSource through
        // UltiTools.getInstance().getDataStore() for the @Transactional advisor --
        // CALLS_REAL_METHODS lets the interface's own default getDataSource(DataScope) run and
        // throw UnsupportedOperationException, which wireAop already handles as "this backend has
        // no @Transactional support".
        com.ultikits.ultitools.interfaces.DataStore dataStore =
                mock(com.ultikits.ultitools.interfaces.DataStore.class, CALLS_REAL_METHODS);

        UltiTools mockUltiTools = mock(UltiTools.class);
        lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(mockUltiTools.getLogger()).thenReturn(
                Logger.getLogger("PluginManagerRegistrationParityTest"));
        lenient().when(mockUltiTools.getDataStore()).thenReturn(dataStore);
        lenient().when(mockUltiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
        lenient().when(mockUltiTools.getConfigManager()).thenReturn(configManager);
        // UltiToolsPlugin's no-arg constructor reads getConfig().getString("language") (Bukkit's
        // own JavaPlugin.getConfig(), unstubbed on a full mock returns null and NPEs) -- an empty
        // YamlConfiguration is enough; a missing "language" key just falls through to the {}
        // default language.
        lenient().when(mockUltiTools.getConfig())
                .thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        return mockUltiTools;
    }

    private PluginManager newPluginManagerWithLoader(ClassLoader loader) throws Exception {
        PluginManager pluginManager = new PluginManager();
        Field classLoaderField = PluginManager.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        classLoaderField.set(pluginManager, loader);
        return pluginManager;
    }

    /** Invokes the private {@code initializePlugin(ClassLoader, Class, Object...)} reflectively. */
    private Object invokeInitializePlugin(PluginManager pluginManager, ClassLoader loader, Class<?> pluginClass)
            throws Exception {
        Method initializePlugin = PluginManager.class.getDeclaredMethod(
                "initializePlugin", ClassLoader.class, Class.class, Object[].class);
        initializePlugin.setAccessible(true);
        return initializePlugin.invoke(pluginManager, loader, pluginClass, new Object[0]);
    }

    /** A trivial config entity, registered directly by tests rather than through the fixture jar --
     * {@link AbstractConfigEntity} is not an {@code UltiToolsPlugin} subclass, so it needs no
     * jar-backed {@code CodeSource} and can live in this file. */
    private static final class ParityFixtureConfigEntity extends AbstractConfigEntity {
        ParityFixtureConfigEntity() {
            super("config/parity.yml");
        }
    }

    // ===== Task 1: setContext runs BEFORE refresh() on the JAR load path =====

    @Nested
    @DisplayName("a @PostConstruct method can observe a non-null context (difference #5)")
    class PostConstructContextTests {

        @Test
        @DisplayName("initializePlugin: getContext() is non-null when @PostConstruct runs")
        void initializePlugin_postConstructObservesNonNullContext() throws Exception {
            File jar = buildFixtureJar("parity-fixture.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loader = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClass = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loader);

            ConfigManager configManager = mock(ConfigManager.class);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager pluginManager = newPluginManagerWithLoader(loader);

            Object plugin;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                plugin = invokeInitializePlugin(pluginManager, loader, pluginClass);
            }

            assertNotNull(plugin, "initializePlugin must not refuse this module");
            Method wasContextNonNull = pluginClass.getMethod("wasContextNonNullDuringPostConstruct");
            assertTrue((Boolean) wasContextNonNull.invoke(plugin),
                    "the module's own @PostConstruct method must observe getContext() != null -- "
                            + "before WIRE-05 Task 1, initializePlugin set the context AFTER "
                            + "refresh(), so this always read false on the JAR load path");
        }
    }
}

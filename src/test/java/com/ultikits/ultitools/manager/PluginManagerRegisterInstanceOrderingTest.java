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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.testfixtures.registersingletonordering.OrderingFixtureModule;
import com.ultikits.testfixtures.registersingletonordering.OrderingFixtureService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataStore;

/**
 * WR-03: {@code PluginManager.register(UltiToolsPlugin)} -- a second, public overload distinct
 * from {@code initializePlugin}/{@code loadPluginMainClass} -- repeats the exact ordering bug
 * T-03-27 already fixed in that sibling method: it called {@code registerSingleton} on the plugin
 * instance before any component scan, so an {@code @Autowired} field on the plugin's own class
 * could never resolve against its own scanned {@code @Service} beans.
 * <p>
 * Reuses the {@link OrderingFixtureModule}/{@link OrderingFixtureService} fixture pair
 * {@code RegisterSingletonAssemblyTest.InitializePluginOrdering} already established for the
 * sibling method's own T-03-27 regression, and the same jar-backed-{@code CodeSource} loading
 * trick {@code UltiToolsPlugin}'s no-arg constructor requires -- but drives it through
 * {@code register(UltiToolsPlugin)} instead of {@code initializePlugin}, since that is the second,
 * independently-broken call site.
 */
@DisplayName("PluginManager.register(UltiToolsPlugin): scan-before-registerSingleton ordering (WR-03)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerRegisterInstanceOrderingTest {

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
     * Child-first for the fixture package only -- identical rationale to the copy in
     * {@code RegisterSingletonAssemblyTest.InitializePluginOrdering}: {@code UltiToolsPlugin}'s
     * no-arg constructor needs the plugin class's own {@code CodeSource} to be a real jar file,
     * which {@code target/test-classes} is not.
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

    private File buildFixtureJar() throws Exception {
        File jar = new File(tempDir, "register-instance-ordering-fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            writeClassEntry(output, OrderingFixtureService.class);
            writeClassEntry(output, OrderingFixtureModule.class);

            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write("name: OrderingFixtureModule\nversion: 1.0.0\n"
                    .getBytes(StandardCharsets.UTF_8));
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

    @Test
    @DisplayName("the plugin instance's own @Autowired field is populated after register(UltiToolsPlugin) returns")
    void autowiredFieldOnPluginInstanceIsPopulatedAfterRegisterInstance() throws Exception {
        File jar = buildFixtureJar();
        FixtureJarClassLoader loader = new FixtureJarClassLoader(jar.toURI().toURL(),
                Thread.currentThread().getContextClassLoader(),
                "com.ultikits.testfixtures.registersingletonordering.");
        Class<?> pluginClass = Class.forName(
                "com.ultikits.testfixtures.registersingletonordering.OrderingFixtureModule",
                true, loader);

        DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
        when(mockDependenceManagers.getContext()).thenReturn(new SimpleContainer());

        // wireAop (called by register(UltiToolsPlugin) before refresh()) resolves a DataSource
        // through UltiTools.getInstance().getDataStore() for the @Transactional advisor --
        // CALLS_REAL_METHODS lets the interface's own default getDataSource(DataScope) run and
        // throw UnsupportedOperationException, which wireAop already handles as "this backend has
        // no @Transactional support", exactly like PluginManagerTest's own
        // CompatibilityGateOrderingTests setup does for the identical need.
        DataStore dataStore = mock(DataStore.class, CALLS_REAL_METHODS);

        UltiTools mockUltiTools = mock(UltiTools.class);
        lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(mockUltiTools.getLogger()).thenReturn(
                Logger.getLogger("PluginManagerRegisterInstanceOrderingTest"));
        lenient().when(mockUltiTools.getDataStore()).thenReturn(dataStore);
        lenient().when(mockUltiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
        lenient().when(mockUltiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
        // UltiToolsPlugin's no-arg constructor reads getConfig().getString("language") (Bukkit's
        // own JavaPlugin.getConfig(), unstubbed on a full mock returns null and NPEs) -- an empty
        // YamlConfiguration is enough; a missing "language" key just falls through to the {}
        // default language.
        lenient().when(mockUltiTools.getConfig())
                .thenReturn(new org.bukkit.configuration.file.YamlConfiguration());

        PluginManager pluginManager = new PluginManager();
        // register(UltiToolsPlugin) reads PluginManager's own `classLoader` field (set normally
        // by init(ClassLoader), not called here) to pass to SimpleContainer.setClassLoader(...),
        // which ComponentScanner then uses to resolve the fixture package's classpath resource.
        // Point it at the same isolated loader the plugin instance itself was constructed with,
        // so the scan finds OrderingFixtureService in the same jar/loader as the plugin class.
        Field classLoaderField = PluginManager.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        classLoaderField.set(pluginManager, loader);

        Object plugin;
        // passesCompatibilityGates -> UltiTools.getPluginVersion() -> getEnv() reads env.yml
        // through getInstance().getTextResource(String), which is protected on Bukkit's own
        // JavaPlugin and cannot be stubbed from this package -- so the STATIC call site is
        // stubbed directly instead, the same workaround PluginManagerTest's own
        // CompatibilityGateOrderingTests and RegisterSingletonAssemblyTest.InitializePluginOrdering
        // both use for the identical problem.
        try (MockedStatic<UltiTools> ultiToolsStatic =
                mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
            ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
            ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

            plugin = pluginClass.getDeclaredConstructor().newInstance();

            Method register = PluginManager.class.getDeclaredMethod("register", UltiToolsPlugin.class);
            boolean result = (Boolean) register.invoke(pluginManager, plugin);

            assertTrue(result, "register(UltiToolsPlugin) must not refuse this module");
        }

        Field serviceField = pluginClass.getDeclaredField("service");
        serviceField.setAccessible(true);
        assertNotNull(serviceField.get(plugin),
                "the plugin instance's own @Autowired field must be populated -- inert case: "
                        + "asserting only that register(UltiToolsPlugin) returned true passes even "
                        + "if the field is null, which is the WR-03 defect this fix exists to "
                        + "delete. Before the fix, registerSingleton ran on the plugin instance "
                        + "BEFORE any component scan, so this container had no @Service beans yet "
                        + "and the @Autowired field could never resolve.");
    }
}

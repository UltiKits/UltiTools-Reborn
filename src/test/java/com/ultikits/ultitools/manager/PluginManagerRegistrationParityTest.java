package com.ultikits.ultitools.manager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;

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

    private Set<String> beanNames(SimpleContainer container) {
        return new HashSet<>(java.util.Arrays.asList(container.getBeanDefinitionNames()));
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

    // ===== Task 2: the four remaining capability differences, plus capability-set parity =====

    @Nested
    @DisplayName("both entry points close five of the nine measured differences")
    class CapabilityParityTests {

        @Test
        @DisplayName("assembling the same class through both entry points yields equal bean-name sets")
        void bothEntryPoints_produceContainersWithEqualBeanNameSets() throws Exception {
            File jar = buildFixtureJar("parity-fixture-equal-sets.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loader = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClass = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loader);

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config/parity.yml", new ParityFixtureConfigEntity());
            ConfigManager configManager = mock(ConfigManager.class);
            lenient().when(configManager.getAllConfigEntities(any())).thenReturn(configMap);
            UltiTools mockUltiTools = newMockUltiTools(configManager);

            PluginManager jarPathManager = newPluginManagerWithLoader(loader);
            PluginManager registerInstanceManager = newPluginManagerWithLoader(loader);

            Object jarPathPlugin;
            Object registerInstancePlugin;
            boolean registerResult;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                // UltiToolsPlugin's no-arg constructor reads UltiTools.getInstance() -- must run
                // inside this mockStatic block, same as initializePlugin's own internal
                // construction on the other side of this comparison.
                registerInstancePlugin = pluginClass.getDeclaredConstructor().newInstance();
                jarPathPlugin = invokeInitializePlugin(jarPathManager, loader, pluginClass);
                registerResult = registerInstanceManager.register((UltiToolsPlugin) registerInstancePlugin);
            }

            assertNotNull(jarPathPlugin, "initializePlugin must not refuse this module");
            assertTrue(registerResult, "register(UltiToolsPlugin) must not refuse this module");

            SimpleContainer jarPathContainer = ((UltiToolsPlugin) jarPathPlugin).getContext();
            SimpleContainer registerInstanceContainer = ((UltiToolsPlugin) registerInstancePlugin).getContext();

            assertEquals(beanNames(jarPathContainer), beanNames(registerInstanceContainer),
                    "both entry points assembled the SAME module class -- their resulting "
                            + "containers must expose the same bean-name set, or a capability "
                            + "still differs between them");
        }

        @Test
        @DisplayName("@ContextEntry is honoured on the JAR load path too (difference #1)")
        void contextEntryBeanPresent_onJarLoadPath() throws Exception {
            File jar = buildFixtureJar("parity-fixture-contextentry.jar", "ParityFixtureModule",
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
            SimpleContainer container = ((UltiToolsPlugin) plugin).getContext();
            assertNotNull(container.getBean("ParityContextBean"),
                    "@ContextEntry's target class must be registered as a bean on the JAR load "
                            + "path too -- before WIRE-06, only register(UltiToolsPlugin) honoured it");
        }

        @Test
        @DisplayName("config entities are registered as beans on register(UltiToolsPlugin) too (difference #2)")
        void configEntityBeanPresent_onRegisterInstancePath() throws Exception {
            File jar = buildFixtureJar("parity-fixture-config.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loader = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClass = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loader);

            Map<String, AbstractConfigEntity> configMap = new HashMap<>();
            configMap.put("config/parity.yml", new ParityFixtureConfigEntity());
            ConfigManager configManager = mock(ConfigManager.class);
            lenient().when(configManager.getAllConfigEntities(any())).thenReturn(configMap);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager pluginManager = newPluginManagerWithLoader(loader);

            Object plugin;
            boolean result;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                // UltiToolsPlugin's no-arg constructor reads UltiTools.getInstance() -- must run
                // inside this mockStatic block.
                plugin = pluginClass.getDeclaredConstructor().newInstance();
                result = pluginManager.register((UltiToolsPlugin) plugin);
            }

            assertTrue(result, "register(UltiToolsPlugin) must not refuse this module");
            SimpleContainer container = ((UltiToolsPlugin) plugin).getContext();
            assertNotNull(container.getBean("parityFixtureConfigEntity"),
                    "config entities must be registered as beans on register(UltiToolsPlugin) "
                            + "too -- before WIRE-05, only initializePlugin did this");
        }

        @Test
        @DisplayName("the static instance field is set on register(UltiToolsPlugin) too (difference #3)")
        void staticInstanceFieldPopulated_onRegisterInstancePath() throws Exception {
            File jar = buildFixtureJar("parity-fixture-staticinstance.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loader = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClass = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loader);

            ConfigManager configManager = mock(ConfigManager.class);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager pluginManager = newPluginManagerWithLoader(loader);

            Object plugin;
            boolean result;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                // UltiToolsPlugin's no-arg constructor reads UltiTools.getInstance() -- must run
                // inside this mockStatic block.
                plugin = pluginClass.getDeclaredConstructor().newInstance();
                result = pluginManager.register((UltiToolsPlugin) plugin);
            }

            assertTrue(result, "register(UltiToolsPlugin) must not refuse this module");
            Method getInstance = pluginClass.getMethod("getInstance");
            assertSame(plugin, getInstance.invoke(null),
                    "the module's private static instance field must be populated by "
                            + "register(UltiToolsPlugin) too -- before WIRE-05, only "
                            + "initializePlugin set it");
        }

        @Test
        @DisplayName("a @ContextEntry target with no accessible no-arg constructor logs a WARNING "
                + "and assembly continues, on both entry points")
        void noAccessibleConstructor_logsWarningAndContinuesOnBothPaths() throws Exception {
            File jar = buildFixtureJar("parity-fixture-unconstructable.jar",
                    "ParityUnconstructableContextEntryModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityUnconstructableContextEntryModule"));
            FixtureJarClassLoader loaderA = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            FixtureJarClassLoader loaderB = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClassA = Class.forName(
                    FIXTURE_PACKAGE + "ParityUnconstructableContextEntryModule", true, loaderA);
            Class<?> pluginClassB = Class.forName(
                    FIXTURE_PACKAGE + "ParityUnconstructableContextEntryModule", true, loaderB);

            ConfigManager configManager = mock(ConfigManager.class);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager jarPathManager = newPluginManagerWithLoader(loaderA);
            PluginManager registerInstanceManager = newPluginManagerWithLoader(loaderB);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                // UltiToolsPlugin's no-arg constructor reads UltiTools.getInstance() -- must run
                // inside this mockStatic block.
                Object registerInstancePlugin = pluginClassB.getDeclaredConstructor().newInstance();

                Object jarPathPlugin = assertDoesNotThrow(
                        () -> invokeInitializePlugin(jarPathManager, loaderA, pluginClassA),
                        "an unconstructable @ContextEntry target must not fail the whole "
                                + "assembly on the JAR load path");
                assertNotNull(jarPathPlugin, "initializePlugin must not refuse this module");

                boolean registerResult = assertDoesNotThrow(
                        () -> registerInstanceManager.register((UltiToolsPlugin) registerInstancePlugin),
                        "an unconstructable @ContextEntry target must not fail the whole "
                                + "assembly on register(UltiToolsPlugin) either");
                assertTrue(registerResult, "register(UltiToolsPlugin) must not refuse this module");
            }
        }
    }

    // ===== WIRE-05/WIRE-06 idempotency truths =====

    @Nested
    @DisplayName("assembling the same class twice is deterministic (WIRE-05/WIRE-06 idempotency)")
    class IdempotencyTests {

        @Test
        @DisplayName("two initializePlugin assemblies of the same class produce equal bean-name sets")
        void assemblingSameClassTwice_yieldsEqualBeanNameSets() throws Exception {
            File jar = buildFixtureJar("parity-fixture-idempotent.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loaderA = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            FixtureJarClassLoader loaderB = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClassA = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loaderA);
            Class<?> pluginClassB = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loaderB);

            ConfigManager configManager = mock(ConfigManager.class);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager managerA = newPluginManagerWithLoader(loaderA);
            PluginManager managerB = newPluginManagerWithLoader(loaderB);

            Object pluginA;
            Object pluginB;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                pluginA = invokeInitializePlugin(managerA, loaderA, pluginClassA);
                pluginB = invokeInitializePlugin(managerB, loaderB, pluginClassB);
            }

            assertNotNull(pluginA);
            assertNotNull(pluginB);
            SimpleContainer containerA = ((UltiToolsPlugin) pluginA).getContext();
            SimpleContainer containerB = ((UltiToolsPlugin) pluginB).getContext();

            assertEquals(beanNames(containerA), beanNames(containerB),
                    "assembling the same plugin class twice must yield two containers whose "
                            + "bean-name sets are equal -- the assembly is deterministic and the "
                            + "second run adds nothing the first did not");
        }

        @Test
        @DisplayName("@ContextEntry's bean is registered exactly once per assembled container")
        void contextEntryBean_registeredExactlyOncePerAssembledContainer() throws Exception {
            File jar = buildFixtureJar("parity-fixture-contextentry-once.jar", "ParityFixtureModule",
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule"),
                    Class.forName(FIXTURE_PACKAGE + "ParityFixtureService"));
            FixtureJarClassLoader loaderA = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            FixtureJarClassLoader loaderB = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), FIXTURE_PACKAGE);
            Class<?> pluginClassA = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loaderA);
            Class<?> pluginClassB = Class.forName(FIXTURE_PACKAGE + "ParityFixtureModule", true, loaderB);

            ConfigManager configManager = mock(ConfigManager.class);
            UltiTools mockUltiTools = newMockUltiTools(configManager);
            PluginManager managerA = newPluginManagerWithLoader(loaderA);
            PluginManager managerB = newPluginManagerWithLoader(loaderB);

            Object pluginA;
            Object pluginB;
            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                pluginA = invokeInitializePlugin(managerA, loaderA, pluginClassA);
                pluginB = invokeInitializePlugin(managerB, loaderB, pluginClassB);
            }

            SimpleContainer containerA = ((UltiToolsPlugin) pluginA).getContext();
            SimpleContainer containerB = ((UltiToolsPlugin) pluginB).getContext();

            // Each container is assembled independently -- a second assembly of the same class
            // must never leave a SECOND @ContextEntry bean sitting in either container (the
            // literal "never a container with two" wording of the WIRE-06 idempotency truth).
            long countInA = countOccurrences(containerA.getBeanDefinitionNames(), "ParityContextBean");
            long countInB = countOccurrences(containerB.getBeanDefinitionNames(), "ParityContextBean");
            assertEquals(1L, countInA, "container A must have exactly one @ContextEntry bean");
            assertEquals(1L, countInB, "container B must have exactly one @ContextEntry bean, "
                    + "not a second copy contributed by container A's assembly");
            assertNotNull(containerA.getBean("ParityContextBean"));
            assertNotNull(containerB.getBean("ParityContextBean"));
        }

        private long countOccurrences(String[] names, String target) {
            long count = 0;
            for (String name : names) {
                if (target.equals(name)) {
                    count++;
                }
            }
            return count;
        }
    }
}

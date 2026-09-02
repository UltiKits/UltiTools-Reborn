package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * UAT-03 / UAT-04 backstop: module assembly is isolated per module.
 * <ul>
 *   <li><b>UAT-03</b> -- assembling the SAME module class on two threads at once (one through
 *       {@code register(UltiToolsPlugin)}, one through the JAR-load path) yields two structurally
 *       independent {@link SimpleContainer}s. The only write the two share is the plugin class's
 *       own {@code static instance} field, which {@code setPluginStaticInstance} sets by design.</li>
 *   <li><b>UAT-04</b> -- two DIFFERENT modules assembled in sequence cannot see each other's
 *       beans, and specifically the second module's {@code @PostConstruct} -- which runs DURING
 *       its assembly -- observes none of the first module's beans.</li>
 * </ul>
 * {@code PluginManagerRegistrationParityTest} proves the two entry points converge on the same
 * bean-NAME set after assembly completes; it does not probe for shared instances or for leakage
 * observed mid-assembly, which is what these tests add.
 * <p>
 * Reuses that class's jar-backed-{@code CodeSource} classloader trick verbatim: {@code
 * UltiToolsPlugin}'s no-arg constructor needs its class's own {@code CodeSource} to be a real jar,
 * which {@code target/test-classes} is not.
 * <br>
 * UAT-03 / UAT-04 兜底验证：模块装配按模块隔离。UAT-03 验证同一模块类在两个线程上并发装配会得到两个
 * 结构独立的容器（唯一共享写入是设计使然的静态 instance 字段）；UAT-04 验证顺序装配的两个不同模块
 * 互相看不到对方的 bean，且第二个模块**装配过程中**的 {@code @PostConstruct} 也看不到。
 */
@DisplayName("PluginManager per-module container isolation (UAT-03/UAT-04)")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerContainerIsolationTest {

    private static final String ISOLATION_ROOT = "com.ultikits.testfixtures.containerisolation.";
    private static final String ALPHA_PACKAGE = ISOLATION_ROOT + "alpha.";
    private static final String BETA_PACKAGE = ISOLATION_ROOT + "beta.";

    @TempDir
    File tempDir;

    private ExecutorService executor;

    @BeforeEach
    void setUpBukkit() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDownBukkit() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    /** Child-first for one fixture package only -- verbatim from {@code PluginManagerRegistrationParityTest}. */
    private static final class FixtureJarClassLoader extends URLClassLoader {
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
                        // Not in this loader's jar -- fall through to parent-first delegation.
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
                String resourceName = clazz.getName().replace('.', '/') + ".class";
                output.putNextEntry(new JarEntry(resourceName));
                try (InputStream input = clazz.getResourceAsStream("/" + resourceName)) {
                    assertThat(input).as("compiled class resource for %s", clazz.getName()).isNotNull();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: " + pluginYmlName + "\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /**
     * The mocked {@code UltiTools} singleton every assembly path reads through. The single
     * {@code DependenceManagers} context it hands out is the DOCUMENTED shared parent -- the
     * isolation assertions below are about module-local beans, not about that parent.
     */
    private UltiTools newMockUltiTools() {
        DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
        when(mockDependenceManagers.getContext()).thenReturn(new SimpleContainer());

        com.ultikits.ultitools.interfaces.DataStore dataStore =
                mock(com.ultikits.ultitools.interfaces.DataStore.class, CALLS_REAL_METHODS);

        UltiTools mockUltiTools = mock(UltiTools.class);
        lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(mockUltiTools.getLogger())
                .thenReturn(Logger.getLogger("PluginManagerContainerIsolationTest"));
        lenient().when(mockUltiTools.getDataStore()).thenReturn(dataStore);
        lenient().when(mockUltiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
        lenient().when(mockUltiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
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

    /**
     * Plan 07-14 (GEN-04) removed the deprecated with-args initializePlugin(ClassLoader, Class,
     * Object...) overload; every call site here always passed an empty constructorArgs array,
     * which that overload's own javadoc documented as routing straight through to the
     * two-argument overload anyway (SILENT-17) -- retargeting the reflection changes nothing
     * about what this test class observes.
     */
    private Object invokeInitializePlugin(PluginManager pluginManager, ClassLoader loader, Class<?> pluginClass)
            throws Exception {
        Method initializePlugin = PluginManager.class.getDeclaredMethod(
                "initializePlugin", ClassLoader.class, Class.class);
        initializePlugin.setAccessible(true);
        return initializePlugin.invoke(pluginManager, loader, pluginClass);
    }

    private List<String> beanNames(SimpleContainer container) {
        return Arrays.asList(container.getBeanDefinitionNames());
    }

    // ===== UAT-03: concurrent assembly of the SAME class through BOTH entry points =====

    @Nested
    @DisplayName("UAT-03: concurrent assembly yields independent containers")
    class ConcurrentAssemblyTests {

        @RepeatedTest(8)
        @DisplayName("register(UltiToolsPlugin) and the JAR-load path on two threads at once")
        // PMD's MethodNamingConventions only treats @Test as a JUnit 5 test method, so a
        // @RepeatedTest method is checked against methodPattern (instance methods) instead of
        // junit5TestPattern. The name follows this suite's scenario_expectation convention.
        @SuppressWarnings("PMD.MethodNamingConventions")
        void concurrentAssembly_producesIndependentContainers() throws Exception {
            File jar = buildFixtureJar("isolation-alpha-concurrent.jar", "IsolationAlphaModule",
                    Class.forName(ALPHA_PACKAGE + "IsolationAlphaModule"),
                    Class.forName(ALPHA_PACKAGE + "IsolationAlphaService"));
            try (FixtureJarClassLoader loader = new FixtureJarClassLoader(
                    jar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), ALPHA_PACKAGE)) {
            Class<?> pluginClass = Class.forName(ALPHA_PACKAGE + "IsolationAlphaModule", true, loader);

            // WR-01: one mock set PER THREAD, never shared. Mockito records every invocation into
            // per-mock unsynchronized state, so two threads driving a whole plugin assembly through
            // the same mock is a latent flake independent of what this test measures. Both sets are
            // built here on the main thread so mock CREATION is not concurrent either. This matches
            // ConfigValidationConcurrencyTest, which already gives each thread its own plugin mock.
            // No assertion below depends on the two threads sharing a parent container:
            // getBeanDefinitionNames() is local-only, and the parent-chain reachability claim lives
            // in SequentialAssemblyTests, which is single-threaded and still shares one mock set.
            UltiTools jarPathUltiTools = newMockUltiTools();
            UltiTools registerUltiTools = newMockUltiTools();
            PluginManager jarPathManager = newPluginManagerWithLoader(loader);
            PluginManager registerManager = newPluginManagerWithLoader(loader);

            CountDownLatch startGate = new CountDownLatch(1);

            // Mockito's MockedStatic is THREAD-LOCAL, so each thread installs its own -- both
            // resolving UltiTools.getInstance() to the same mock instance.
            Callable<UltiToolsPlugin> jarPathTask = () -> {
                startGate.await();
                try (MockedStatic<UltiTools> statics = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                    statics.when(UltiTools::getInstance).thenReturn(jarPathUltiTools);
                    statics.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
                    return (UltiToolsPlugin) invokeInitializePlugin(jarPathManager, loader, pluginClass);
                }
            };
            Callable<UltiToolsPlugin> registerTask = () -> {
                startGate.await();
                try (MockedStatic<UltiTools> statics = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                    statics.when(UltiTools::getInstance).thenReturn(registerUltiTools);
                    statics.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
                    UltiToolsPlugin plugin =
                            (UltiToolsPlugin) pluginClass.getDeclaredConstructor().newInstance();
                    registerManager.register(plugin);
                    return plugin;
                }
            };

            Future<UltiToolsPlugin> jarPathFuture = executor.submit(jarPathTask);
            Future<UltiToolsPlugin> registerFuture = executor.submit(registerTask);
            startGate.countDown();

            UltiToolsPlugin jarPathPlugin = jarPathFuture.get(60, TimeUnit.SECONDS);
            UltiToolsPlugin registerPlugin = registerFuture.get(60, TimeUnit.SECONDS);

            assertThat(jarPathPlugin).as("the JAR-load path must not refuse this module").isNotNull();
            assertThat(registerPlugin).as("register(UltiToolsPlugin) must not refuse this module").isNotNull();

            SimpleContainer jarPathContainer = jarPathPlugin.getContext();
            SimpleContainer registerContainer = registerPlugin.getContext();

            assertThat(jarPathContainer)
                    .as("each thread must own a DISTINCT container object -- one shared container "
                            + "would mean the two assemblies wrote into the same bean registry")
                    .isNotNull()
                    .isNotSameAs(registerContainer);
            assertThat(registerContainer).isNotNull();

            assertThat(beanNames(jarPathContainer))
                    .as("guard against a vacuous comparison: two EMPTY bean-name sets would satisfy "
                            + "the equality below without either assembly having done anything")
                    .isNotEmpty()
                    .as("independent containers must still be structurally equal -- the same class "
                            + "assembled twice yields the same bean-name set")
                    .containsExactlyInAnyOrderElementsOf(beanNames(registerContainer));

            // No cross-thread bean leakage: same NAMES, different INSTANCES.
            Method getService = pluginClass.getMethod("getService");
            Object jarPathService = getService.invoke(jarPathPlugin);
            Object registerService = getService.invoke(registerPlugin);
            assertThat(jarPathService)
                    .as("each container's @Autowired service must have been injected")
                    .isNotNull();
            assertThat(registerService).isNotNull();
            assertThat(jarPathService)
                    .as("the two containers must hold DIFFERENT service instances -- the same "
                            + "instance in both would be a bean leaking across the two assemblies")
                    .isNotSameAs(registerService);

            assertThat(jarPathPlugin)
                    .as("the two threads must have assembled two different plugin objects")
                    .isNotSameAs(registerPlugin);

            // The one sanctioned shared write: the plugin class's own static instance field.
            Object staticInstance = pluginClass.getMethod("getInstance").invoke(null);
            assertThat(staticInstance)
                    .as("setPluginStaticInstance writes this field by design; whichever thread ran "
                            + "last wins, and that is the ONLY observable shared write")
                    .isIn(jarPathPlugin, registerPlugin);
            }
        }
    }

    // ===== UAT-04: sequential assembly of two DIFFERENT modules =====

    @Nested
    @DisplayName("UAT-04: a module's @PostConstruct sees none of another module's beans")
    class SequentialAssemblyTests {

        @Test
        @DisplayName("module B's @PostConstruct snapshot contains no bean of module A")
        void sequentialAssembly_noCrossModuleVisibilityDuringAssembly() throws Exception {
            File alphaJar = buildFixtureJar("isolation-alpha.jar", "IsolationAlphaModule",
                    Class.forName(ALPHA_PACKAGE + "IsolationAlphaModule"),
                    Class.forName(ALPHA_PACKAGE + "IsolationAlphaService"));
            File betaJar = buildFixtureJar("isolation-beta.jar", "IsolationBetaModule",
                    Class.forName(BETA_PACKAGE + "IsolationBetaModule"),
                    Class.forName(BETA_PACKAGE + "IsolationBetaService"));

            try (FixtureJarClassLoader alphaLoader = new FixtureJarClassLoader(
                    alphaJar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), ALPHA_PACKAGE);
                 FixtureJarClassLoader betaLoader = new FixtureJarClassLoader(
                    betaJar.toURI().toURL(), Thread.currentThread().getContextClassLoader(), BETA_PACKAGE)) {

            Class<?> alphaClass = Class.forName(ALPHA_PACKAGE + "IsolationAlphaModule", true, alphaLoader);
            Class<?> betaClass = Class.forName(BETA_PACKAGE + "IsolationBetaModule", true, betaLoader);

            UltiTools mockUltiTools = newMockUltiTools();

            UltiToolsPlugin alphaPlugin;
            UltiToolsPlugin betaPlugin;
            try (MockedStatic<UltiTools> statics = mockStatic(UltiTools.class, CALLS_REAL_METHODS)) {
                statics.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                statics.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                // A first, fully assembled...
                alphaPlugin = (UltiToolsPlugin) invokeInitializePlugin(
                        newPluginManagerWithLoader(alphaLoader), alphaLoader, alphaClass);
                // ...then B, whose @PostConstruct snapshots what it can see mid-assembly.
                betaPlugin = (UltiToolsPlugin) invokeInitializePlugin(
                        newPluginManagerWithLoader(betaLoader), betaLoader, betaClass);
            }

            assertThat(alphaPlugin).as("module A must assemble").isNotNull();
            assertThat(betaPlugin).as("module B must assemble").isNotNull();

            @SuppressWarnings("unchecked")
            List<String> betaSnapshot = (List<String>)
                    betaClass.getMethod("getBeanNamesDuringPostConstruct").invoke(betaPlugin);

            assertThat(betaSnapshot)
                    .as("the snapshot must be non-empty, or the assertion below is vacuous -- "
                            + "B's @PostConstruct has to have actually run with a live context")
                    .isNotEmpty()
                    .as("B's own scanned service must be visible to B")
                    .anyMatch(name -> name.toLowerCase().contains("isolationbetaservice"));

            assertThat(betaSnapshot)
                    .as("B's container must NOT expose any bean belonging to A -- a hit here means "
                            + "a previously assembled module leaked into a later one's registry")
                    .noneMatch(name -> name.toLowerCase().contains("isolationalpha"));

            // containsBean walks the parent chain, so this also rules out reaching A's beans
            // through the shared DependenceManagers parent context.
            assertThat(betaPlugin.getContext().containsBean("isolationAlphaService"))
                    .as("A's service must be unreachable from B even through the parent chain")
                    .isFalse();
            assertThat(alphaPlugin.getContext().containsBean("isolationBetaService"))
                    .as("and symmetrically, B's service must be unreachable from A")
                    .isFalse();

            assertThat(alphaPlugin.getContext())
                    .as("two modules must never share one container object")
                    .isNotSameAs(betaPlugin.getContext());
            }
        }
    }
}

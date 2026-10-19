package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
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
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;

/**
 * Phase 20.4 plan 03: the first assembly of a module container waits for everything it needs,
 * whichever entry point loads the module.
 * <p>
 * Every case runs twice, once through {@link PluginManager#register(Class)} with a fixture JAR and
 * once through {@link PluginManager#register(UltiToolsPlugin)} with an instance constructed from
 * the same JAR. The fixture classes live under {@code com.ultikits.testfixtures.firstassembly} (see
 * that package's javadoc); each scenario's JAR is built at test time, and the isolated loader
 * answers the scenario package's resource lookup with that JAR, so the component scan reads the
 * JAR's entries in the order the test wrote them.
 */
@DisplayName("First assembly of a module container (20.4-03)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // sets PluginManager's private class loader field
class PluginManagerFirstAssemblyTest {

    private static final String FIXTURE_ROOT = "com.ultikits.testfixtures.firstassembly.";
    private static final String AOP_MODULE = FIXTURE_ROOT + "aop.AopReadyFixtureModule";
    private static final String CONFIG_MODULE = FIXTURE_ROOT + "config.ConfigReadyFixtureModule";
    private static final String FACTORY_MODULE = FIXTURE_ROOT + "factory.FactoryOrderFixtureModule";
    private static final String CONFIG_AUTOWIRE_MODULE = FIXTURE_ROOT + "configautowire.ConfigAutowireFixtureModule";
    private static final String CONTEXT_ENTRY_MODULE = FIXTURE_ROOT + "contextentry.ContextEntryFixtureModule";
    private static final String REFUSED_CONTEXT_ENTRY_MODULE =
            FIXTURE_ROOT + "contextentryrefused.RefusedContextEntryFixtureModule";
    private static final String CONTEXT_ENTRY_KEY =
            com.ultikits.testfixtures.firstassembly.contextentry.ContextEntryFixtureModule.COMPLETER_KEY;
    private static final String REFUSED_CONTEXT_ENTRY_KEY =
            com.ultikits.testfixtures.firstassembly.contextentryrefused.RefusedContextEntryFixtureModule.COMPLETER_KEY;

    /** The two module-registration entry points. */
    enum EntryPoint { REGISTER_CLASS, REGISTER_INSTANCE }

    @TempDir
    File tempDir;

    private UltiTools ultiTools;
    private ConfigManager configManager;
    private SimpleContainer parentContainer;
    private MockedStatic<UltiTools> ultiToolsStatic;
    private final List<LogRecord> warnings = Collections.synchronizedList(new ArrayList<>());
    private final Handler warningCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
            // Nothing buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        Bukkit.getLogger().addHandler(warningCapture);

        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        parentContainer = new SimpleContainer();
        lenient().when(dependenceManagers.getContext()).thenReturn(parentContainer);
        configManager = mock(ConfigManager.class);

        ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("PluginManagerFirstAssemblyTest"));
        lenient().when(ultiTools.getDataStore()).thenReturn(mock(DataStore.class, CALLS_REAL_METHODS));
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getConfigManager()).thenReturn(configManager);
        lenient().when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
        lenient().when(ultiTools.getListenerManager()).thenReturn(mock(ListenerManager.class));
        // UltiToolsPlugin's no-arg constructor reads getConfig().getString("language").
        lenient().when(ultiTools.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());

        ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
        ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        TabCompletionManager.getInstance().unregister(CONTEXT_ENTRY_KEY);
        TabCompletionManager.getInstance().unregister(REFUSED_CONTEXT_ENTRY_KEY);
        Bukkit.getLogger().removeHandler(warningCapture);
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ===== Fixture JAR and loading =====

    /**
     * Child-first for one scenario package: classes under {@code isolatedPrefix} come from the
     * fixture JAR (falling back to the parent for a class the JAR does not hold), and a resource
     * lookup for that package's path answers with the JAR, so {@code ComponentScanner} scans the
     * JAR in its entry order rather than {@code target/test-classes}.
     */
    static final class FixtureJarClassLoader extends URLClassLoader {
        private final String isolatedPrefix;
        private final String isolatedPath;
        private final URL jarRoot;

        FixtureJarClassLoader(File jar, ClassLoader parent, String isolatedPrefix) throws MalformedURLException {
            super(new URL[]{jar.toURI().toURL()}, parent);
            this.isolatedPrefix = isolatedPrefix;
            this.isolatedPath = isolatedPrefix.replace('.', '/');
            this.jarRoot = new URL("jar:" + jar.toURI().toURL() + "!/");
        }

        @Override
        public URL getResource(String name) {
            // Only the package directory itself: a class-file lookup (ByteBuddy reads class files
            // through getResource) must still find the real entry.
            if (name.equals(isolatedPath) || (name + "/").equals(isolatedPath)) {
                return jarRoot;
            }
            return super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && name.startsWith(isolatedPrefix)) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Not in this JAR: normal parent-first delegation below.
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

    /**
     * Writes a module JAR holding {@code classes} in the given order, then {@code plugin.yml}.
     *
     * @param pluginName the module's {@code plugin.yml} name
     * @param classes    the classes to package, in entry order
     * @return the JAR
     */
    File buildFixtureJar(String pluginName, Class<?>... classes) throws Exception {
        File jar = new File(tempDir, pluginName + "-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Class<?> clazz : classes) {
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
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: " + pluginName + "\nversion: 1.0.0\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /** A loader isolating the package of {@code moduleClassName}, over a JAR of {@code classes}. */
    FixtureJarClassLoader loaderFor(String moduleClassName, String pluginName, Class<?>... classes) throws Exception {
        String scenarioPackage = moduleClassName.substring(0, moduleClassName.lastIndexOf('.') + 1);
        return new FixtureJarClassLoader(buildFixtureJar(pluginName, classes),
                Thread.currentThread().getContextClassLoader(), scenarioPackage);
    }

    /** The outcome of loading one module through one entry point. */
    static final class Loaded {
        final PluginManager manager;
        final boolean registered;
        final UltiToolsPlugin plugin;

        Loaded(PluginManager manager, boolean registered, UltiToolsPlugin plugin) {
            this.manager = manager;
            this.registered = registered;
            this.plugin = plugin;
        }
    }

    /**
     * Loads {@code moduleClassName} from {@code loader} through {@code entryPoint}. Through
     * {@code register(Class)} the instance is read back from the plugin list, so it is {@code null}
     * when the module was refused.
     */
    @SuppressWarnings("unchecked")
    Loaded load(EntryPoint entryPoint, FixtureJarClassLoader loader, String moduleClassName) throws Exception {
        Class<? extends UltiToolsPlugin> pluginClass =
                (Class<? extends UltiToolsPlugin>) Class.forName(moduleClassName, true, loader);
        PluginManager pluginManager = new PluginManager();
        Field classLoaderField = PluginManager.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        classLoaderField.set(pluginManager, loader);
        if (entryPoint == EntryPoint.REGISTER_CLASS) {
            boolean registered = pluginManager.register(pluginClass);
            UltiToolsPlugin listed = null;
            for (UltiToolsPlugin candidate : pluginManager.getPluginList()) {
                if (candidate.getClass() == pluginClass) {
                    listed = candidate;
                }
            }
            return new Loaded(pluginManager, registered, listed);
        }
        UltiToolsPlugin plugin = pluginClass.getDeclaredConstructor().newInstance();
        return new Loaded(pluginManager, pluginManager.register(plugin), plugin);
    }

    /** The WARNING lines logged so far, for assertion messages. */
    String loggedWarnings() {
        StringBuilder text = new StringBuilder();
        synchronized (warnings) {
            for (LogRecord record : warnings) {
                text.append("\n  ").append(record.getMessage());
            }
        }
        return text.toString();
    }

    void assertLoaded(Loaded loaded, EntryPoint entryPoint) {
        assertThat(loaded.registered)
                .as("the module must load through %s; WARNINGs logged:%s", entryPoint, loggedWarnings())
                .isTrue();
        assertThat(loaded.plugin).as("the loaded instance").isNotNull();
    }

    static Object call(Object target, String method) throws Exception {
        Method accessor = target.getClass().getMethod(method);
        return accessor.invoke(target);
    }

    /** Stubs the data store with a JDBC-capable mock and returns the connection it hands out. */
    Connection useJdbcStore() throws Exception {
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        DataStore dataStore = mock(DataStore.class);
        lenient().when(dataStore.getDataSource(any())).thenReturn(dataSource);
        lenient().when(ultiTools.getDataStore()).thenReturn(dataStore);
        return connection;
    }

    Class<?> fixture(String binaryName) throws ClassNotFoundException {
        return Class.forName(binaryName);
    }

    FixtureJarClassLoader aopLoader() throws Exception {
        return loaderFor(AOP_MODULE, "AopReadyFixtureModule", fixture(AOP_MODULE),
                fixture(AOP_MODULE + "$GuardedService"), fixture(AOP_MODULE + "$TransactionalService"));
    }

    FixtureJarClassLoader configLoader() throws Exception {
        return loaderFor(CONFIG_MODULE, "ConfigReadyFixtureModule", fixture(CONFIG_MODULE),
                fixture(CONFIG_MODULE + "$ReadyConfig"), fixture(CONFIG_MODULE + "$ReadyEntity"),
                fixture(CONFIG_MODULE + "$ConfigNeedingService"), fixture(CONFIG_MODULE + "$ScopeObservingService"));
    }

    /**
     * Stubs the data store so {@code ConfigReadyFixtureModule}'s observing service can ask for an
     * operator: the scoped overload answers {@code scoped}, the unscoped legacy overload
     * {@code unscoped}. The fixture entity is not a {@code @Table} entity of the module, so the
     * store's real ownership check would refuse it.
     */
    void stubOperatorStore(DataOperator<?> scoped, DataOperator<?> unscoped) {
        DataStore dataStore = mock(DataStore.class, CALLS_REAL_METHODS);
        doReturn(scoped).when(dataStore).getOperator(any(DataScope.class), any());
        doReturn(unscoped).when(dataStore).getOperator(any(UltiToolsPlugin.class), any());
        lenient().when(ultiTools.getDataStore()).thenReturn(dataStore);
    }

    /** Returns the module's config entity, constructed from {@code loader}, from getAllConfigEntities. */
    AbstractConfigEntity supplyConfigEntity(FixtureJarClassLoader loader) throws Exception {
        AbstractConfigEntity config = (AbstractConfigEntity) Class.forName(CONFIG_MODULE + "$ReadyConfig", true, loader)
                .getDeclaredConstructor().newInstance();
        Map<String, AbstractConfigEntity> configs = new HashMap<>();
        configs.put("config/first-assembly.yml", config);
        lenient().when(configManager.getAllConfigEntities(any())).thenReturn(configs);
        return config;
    }

    // ===== Task 1: configuration, scope and AOP before the first framework-triggered creation =====

    @Nested
    @DisplayName("configuration, data scope and AOP are ready before the first bean is created (C08, C09)")
    class AopAndConfigurationReadyTests {

        @Test
        @DisplayName("C08 register(Class): an @Autowired @ExceptionCatch service is a proxy")
        void c08_autowiredAspectedServiceIsProxied_throughRegisterClass() throws Exception {
            assertAspectedServiceProxied(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C08 register(UltiToolsPlugin): an @Autowired @ExceptionCatch service is a proxy")
        void c08_autowiredAspectedServiceIsProxied_throughRegisterInstance() throws Exception {
            assertAspectedServiceProxied(EntryPoint.REGISTER_INSTANCE);
        }

        @Test
        @DisplayName("C08 register(Class): an @Autowired @Transactional service is a proxy on JDBC")
        void c08_autowiredTransactionalServiceIsProxiedOnJdbc_throughRegisterClass() throws Exception {
            assertTransactionalServiceProxied(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C08 register(UltiToolsPlugin): an @Autowired @Transactional service is a proxy on JDBC")
        void c08_autowiredTransactionalServiceIsProxiedOnJdbc_throughRegisterInstance() throws Exception {
            assertTransactionalServiceProxied(EntryPoint.REGISTER_INSTANCE);
        }

        @Test
        @DisplayName("C09 register(Class): a service needing a config entity loads with it injected")
        void c09_serviceNeedingConfigEntityLoads_throughRegisterClass() throws Exception {
            assertConfigDependentServiceLoads(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C09 register(UltiToolsPlugin): a service needing a config entity loads with it injected")
        void c09_serviceNeedingConfigEntityLoads_throughRegisterInstance() throws Exception {
            assertConfigDependentServiceLoads(EntryPoint.REGISTER_INSTANCE);
        }

        @Test
        @DisplayName("C09 register(Class): @PostConstruct during first assembly sees the data scope and container")
        void c09_postConstructSeesScopeAndContext_throughRegisterClass() throws Exception {
            assertPostConstructSeesScope(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C09 register(UltiToolsPlugin): @PostConstruct during first assembly sees the data scope and container")
        void c09_postConstructSeesScopeAndContext_throughRegisterInstance() throws Exception {
            assertPostConstructSeesScope(EntryPoint.REGISTER_INSTANCE);
        }

        private void assertAspectedServiceProxied(EntryPoint entryPoint) throws Exception {
            // Every AopReadyFixtureModule load uses the JDBC-capable store, so these cases do not
            // depend on the JSON transaction engine.
            useJdbcStore();
            Loaded loaded = load(entryPoint, aopLoader(), AOP_MODULE);
            assertLoaded(loaded, entryPoint);

            Object service = call(loaded.plugin, "getGuardedService");
            assertThat(service).as("the @Autowired @ExceptionCatch service").isNotNull();
            assertAll(
                    () -> assertThat(ProxyFactory.isProxyClass(service.getClass()))
                            .as("the service the module main class @Autowires must be created as an AOP "
                                    + "proxy, but it is a plain %s", service.getClass().getName())
                            .isTrue(),
                    () -> assertThat(invokeGuarded(service))
                            .as("@ExceptionCatch(defaultValue = \"-1\") must turn the thrown exception into -1")
                            .isEqualTo(-1));
        }

        private Object invokeGuarded(Object service) throws Exception {
            try {
                return call(service, "guarded");
            } catch (InvocationTargetException escaped) {
                return "escaped: " + escaped.getCause();
            }
        }

        private void assertTransactionalServiceProxied(EntryPoint entryPoint) throws Exception {
            Connection connection = useJdbcStore();
            Loaded loaded = load(entryPoint, aopLoader(), AOP_MODULE);
            assertLoaded(loaded, entryPoint);

            Object service = call(loaded.plugin, "getTransactionalService");
            assertThat(service).as("the @Autowired @Transactional service").isNotNull();
            assertThat(ProxyFactory.isProxyClass(service.getClass()))
                    .as("the @Transactional service the module main class @Autowires must be an AOP "
                            + "proxy, but it is a plain %s", service.getClass().getName())
                    .isTrue();
            call(service, "write");
            verify(connection).setAutoCommit(false);
            verify(connection).commit();
        }

        private void assertConfigDependentServiceLoads(EntryPoint entryPoint) throws Exception {
            stubOperatorStore(mock(DataOperator.class), mock(DataOperator.class));
            FixtureJarClassLoader loader = configLoader();
            AbstractConfigEntity config = supplyConfigEntity(loader);
            Loaded loaded = load(entryPoint, loader, CONFIG_MODULE);
            assertLoaded(loaded, entryPoint);

            Object service = call(loaded.plugin, "getConfigNeedingService");
            assertThat(service).as("the config-dependent service").isNotNull();
            assertThat(call(service, "getConfig")).as("the injected config entity").isSameAs(config);
        }

        private void assertPostConstructSeesScope(EntryPoint entryPoint) throws Exception {
            DataOperator<?> scoped = mock(DataOperator.class);
            stubOperatorStore(scoped, mock(DataOperator.class));
            FixtureJarClassLoader loader = configLoader();
            // The config entity comes from the parent container here, so this case does not
            // depend on when the module's own config entities are registered (that is the case
            // above); only the data scope and the container are under test.
            parentContainer.registerSingleton("readyConfig",
                    Class.forName(CONFIG_MODULE + "$ReadyConfig", true, loader).getDeclaredConstructor().newInstance());

            Loaded loaded = load(entryPoint, loader, CONFIG_MODULE);
            assertLoaded(loaded, entryPoint);

            Object service = call(loaded.plugin, "getScopeObservingService");
            assertThat(service).as("the observing service").isNotNull();
            assertAll(
                    () -> assertThat(call(service, "getOperatorDuringPostConstruct"))
                            .as("getDataOperator during @PostConstruct must go through the module's "
                                    + "DataScope; the unscoped operator means the DataScope was still null")
                            .isSameAs(scoped),
                    () -> assertThat(call(service, "getContextDuringPostConstruct"))
                            .as("getContext() during @PostConstruct")
                            .isSameAs(loaded.plugin.getContext()));
        }
    }

    // ===== Task 2: every definition is discovered before configuration classes and @Bean products =====

    @Nested
    @DisplayName("a module whose JAR lists its configuration class before the service loads (C51)")
    class FactoryProductDiscoveryTests {

        @Test
        @DisplayName("C51 register(Class): configuration class listed before the service it needs")
        void c51_configurationListedBeforeServiceLoads_throughRegisterClass() throws Exception {
            assertFactoryModuleLoads(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C51 register(UltiToolsPlugin): configuration class listed before the service it needs")
        void c51_configurationListedBeforeServiceLoads_throughRegisterInstance() throws Exception {
            assertFactoryModuleLoads(EntryPoint.REGISTER_INSTANCE);
        }

        private void assertFactoryModuleLoads(EntryPoint entryPoint) throws Exception {
            // JAR entry order: module, configuration class, product, then the service.
            FixtureJarClassLoader loader = loaderFor(FACTORY_MODULE, "FactoryOrderFixtureModule",
                    fixture(FACTORY_MODULE), fixture(FACTORY_MODULE + "$FactoryConfiguration"),
                    fixture(FACTORY_MODULE + "$FactoryProduct"), fixture(FACTORY_MODULE + "$FactoryService"));
            Loaded loaded = load(entryPoint, loader, FACTORY_MODULE);
            assertLoaded(loaded, entryPoint);

            SimpleContainer container = loaded.plugin.getContext();
            Object product = container.getBean("factoryProduct");
            assertThat(product).as("the @Bean product").isNotNull();
            assertThat(call(product, "getService")).as("the product's injected service")
                    .isSameAs(container.getBean(Class.forName(FACTORY_MODULE + "$FactoryService", true, loader)));
        }
    }

    // ===== Review round 1: a config entity may inject a scanned service of its own module =====

    @Nested
    @DisplayName("a config entity that @Autowires a @Service of its own module loads, and a @Bean product still gets the config entity")
    class ConfigEntityAutowiringTests {

        @Test
        @DisplayName("register(Class): the config entity gets the module's service; the @Bean product gets the config entity")
        void configEntityInjectsScannedService_throughRegisterClass() throws Exception {
            assertConfigEntityAutowired(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("register(UltiToolsPlugin): the config entity gets the module's service; the @Bean product gets the config entity")
        void configEntityInjectsScannedService_throughRegisterInstance() throws Exception {
            assertConfigEntityAutowired(EntryPoint.REGISTER_INSTANCE);
        }

        private void assertConfigEntityAutowired(EntryPoint entryPoint) throws Exception {
            // JAR entry order: module, config entity, configuration class, product, then the service.
            FixtureJarClassLoader loader = loaderFor(CONFIG_AUTOWIRE_MODULE, "ConfigAutowireFixtureModule",
                    fixture(CONFIG_AUTOWIRE_MODULE), fixture(CONFIG_AUTOWIRE_MODULE + "$AutowiringConfig"),
                    fixture(CONFIG_AUTOWIRE_MODULE + "$ProductConfiguration"),
                    fixture(CONFIG_AUTOWIRE_MODULE + "$ConfigReadingProduct"),
                    fixture(CONFIG_AUTOWIRE_MODULE + "$HelperService"));
            AbstractConfigEntity config = (AbstractConfigEntity) Class.forName(
                    CONFIG_AUTOWIRE_MODULE + "$AutowiringConfig", true, loader).getDeclaredConstructor().newInstance();
            Map<String, AbstractConfigEntity> configs = new HashMap<>();
            configs.put("config/config-autowire.yml", config);
            lenient().when(configManager.getAllConfigEntities(any())).thenReturn(configs);

            Loaded loaded = load(entryPoint, loader, CONFIG_AUTOWIRE_MODULE);
            assertLoaded(loaded, entryPoint);

            SimpleContainer container = loaded.plugin.getContext();
            Object service = container.getBean(Class.forName(CONFIG_AUTOWIRE_MODULE + "$HelperService", true, loader));
            assertThat(service).as("the module's service").isNotNull();
            assertThat(call(config, "getHelper")).as("the config entity's injected service").isSameAs(service);
            Object product = container.getBean("configReadingProduct");
            assertThat(product).as("the @Bean product").isNotNull();
            assertThat(call(product, "getConfig")).as("the product's injected config entity").isSameAs(config);
            assertThat(container.getBean("autowiringConfig")).as("the config entity by its bean name").isSameAs(config);
        }
    }

    // ===== Task 3: @ContextEntry assembly runs inside the module's registration scope =====

    @Nested
    @DisplayName("a completer registered by a @ContextEntry bean belongs to the module instance (C62)")
    class ContextEntryScopeTests {

        private final TabCompletionManager completions = TabCompletionManager.getInstance();

        @Test
        @DisplayName("C62 register(Class): the @ContextEntry completer is recorded against the module's instance and name")
        void c62_contextEntryCompleterOwnedByModule_throughRegisterClass() throws Exception {
            assertCompleterOwnedByModule(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C62 register(UltiToolsPlugin): the @ContextEntry completer is recorded against the module's instance and name")
        void c62_contextEntryCompleterOwnedByModule_throughRegisterInstance() throws Exception {
            assertCompleterOwnedByModule(EntryPoint.REGISTER_INSTANCE);
        }

        @Test
        @DisplayName("C62 register(Class): a module refused after assembly takes its @ContextEntry completer with it")
        void c62_refusedModuleReleasesContextEntryCompleter_throughRegisterClass() throws Exception {
            assertRefusalReleasesCompleter(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C62 register(UltiToolsPlugin): a module refused after assembly takes its @ContextEntry completer with it")
        void c62_refusedModuleReleasesContextEntryCompleter_throughRegisterInstance() throws Exception {
            assertRefusalReleasesCompleter(EntryPoint.REGISTER_INSTANCE);
        }

        @Test
        @DisplayName("C62 register(Class): unloading the module removes its @ContextEntry completer")
        void c62_unloadReleasesContextEntryCompleter_throughRegisterClass() throws Exception {
            assertUnloadReleasesCompleter(EntryPoint.REGISTER_CLASS);
        }

        @Test
        @DisplayName("C62 register(UltiToolsPlugin): unloading the module removes its @ContextEntry completer")
        void c62_unloadReleasesContextEntryCompleter_throughRegisterInstance() throws Exception {
            assertUnloadReleasesCompleter(EntryPoint.REGISTER_INSTANCE);
        }

        private Loaded loadContextEntryModule(EntryPoint entryPoint) throws Exception {
            FixtureJarClassLoader loader = loaderFor(CONTEXT_ENTRY_MODULE, "ContextEntryFixtureModule",
                    fixture(CONTEXT_ENTRY_MODULE), fixture(CONTEXT_ENTRY_MODULE + "$EntryBean"));
            Loaded loaded = load(entryPoint, loader, CONTEXT_ENTRY_MODULE);
            assertLoaded(loaded, entryPoint);
            assertThat(completions.getCompleter(CONTEXT_ENTRY_KEY))
                    .as("the @ContextEntry bean's @PostConstruct registered its completer").isNotNull();
            return loaded;
        }

        private void assertCompleterOwnedByModule(EntryPoint entryPoint) throws Exception {
            Loaded loaded = loadContextEntryModule(entryPoint);

            // Read ownership through the registry's own API: releasing the instance's registrations
            // takes the completer only if it was recorded against that instance; the returned action
            // puts it back unchanged.
            Runnable giveBack = completions.releaseForSupersede(loaded.plugin);
            assertThat(completions.getCompleter(CONTEXT_ENTRY_KEY))
                    .as("the completer must be recorded against the module instance that loaded it")
                    .isNull();
            giveBack.run();
            assertThat(completions.getCompleter(CONTEXT_ENTRY_KEY)).as("given back").isNotNull();
            assertThat(completions.unregisterByOwner(loaded.plugin.getPluginName()))
                    .as("the completer must be recorded against the module's name").isEqualTo(1);
        }

        private void assertRefusalReleasesCompleter(EntryPoint entryPoint) throws Exception {
            FixtureJarClassLoader loader = loaderFor(REFUSED_CONTEXT_ENTRY_MODULE, "RefusedContextEntryFixtureModule",
                    fixture(REFUSED_CONTEXT_ENTRY_MODULE), fixture(REFUSED_CONTEXT_ENTRY_MODULE + "$EntryBean"));
            Loaded loaded = load(entryPoint, loader, REFUSED_CONTEXT_ENTRY_MODULE);

            assertThat(loaded.registered).as("registerSelf() returns false, so the module is refused").isFalse();
            Object registrations = Class.forName(REFUSED_CONTEXT_ENTRY_MODULE + "$EntryBean", true, loader)
                    .getField("REGISTRATIONS").get(null);
            assertThat(registrations.toString())
                    .as("the @ContextEntry bean registered its completer while the module was assembled")
                    .isEqualTo("1");
            assertThat(completions.getCompleter(REFUSED_CONTEXT_ENTRY_KEY))
                    .as("the refused module's @ContextEntry completer must be released with it")
                    .isNull();
        }

        private void assertUnloadReleasesCompleter(EntryPoint entryPoint) throws Exception {
            Loaded loaded = loadContextEntryModule(entryPoint);

            loaded.manager.unregister(loaded.plugin);

            assertThat(completions.getCompleter(CONTEXT_ENTRY_KEY))
                    .as("unloading the module must remove its @ContextEntry completer")
                    .isNull();
        }
    }
}

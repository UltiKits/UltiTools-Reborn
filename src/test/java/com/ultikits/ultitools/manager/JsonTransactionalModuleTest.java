package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;

/**
 * {@code @Transactional} on {@code datasource.type: json}, end to end through real module loading
 * (Phase 20.4, plan 10; maintainer decision points b and c of 20.4-DECISIONS.md).
 * <p>
 * Two fixture modules are loaded through {@link PluginManager#register(UltiToolsPlugin)} by one
 * {@code PluginManager}, with {@code UltiTools.getDataStore()} answering a real {@link JsonStore}
 * over a temporary folder:
 * <ul>
 *   <li>{@code JsonTxTransactionalModule}, whose service writes two of the module's tables in one
 *       {@code @Transactional} method;</li>
 *   <li>{@code JsonTxOrdinaryModule}, a plain module whose service inserts and reads one table
 *       outside any transaction.</li>
 * </ul>
 * The modules are packaged into JARs at test time and loaded child-first through one isolated
 * class loader, because {@code UltiToolsPlugin}'s no-argument constructor reads {@code plugin.yml}
 * from its own class's {@code CodeSource}. The test therefore reaches the fixture classes by
 * reflection only.
 * <p>
 * Concurrency is forced with latches and bounded waits; the only cross-thread steps are reads.
 */
@Timeout(60)
@DisplayName("A JSON module's @Transactional method commits and rolls back two tables as one (20.4-10)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // sets PluginManager's private class loader field
class JsonTransactionalModuleTest {

    private static final String FIXTURE_ROOT = "com.ultikits.testfixtures.jsontx.";
    private static final String TX_MODULE = FIXTURE_ROOT + "transactional.JsonTxTransactionalModule";
    private static final String ORDINARY_MODULE = FIXTURE_ROOT + "ordinary.JsonTxOrdinaryModule";
    private static final String TX_PLUGIN_NAME = "JsonTxTransactional";
    private static final String ORDINARY_PLUGIN_NAME = "JsonTxOrdinary";

    /** Bounded wait for a handshake that must happen. */
    private static final long HANDSHAKE_SECONDS = 10;

    @TempDir
    File tempDir;

    private UltiTools ultiTools;
    private MockedStatic<UltiTools> ultiToolsStatic;
    private JsonStore store;
    private JsonStore restartedStore;
    private FixtureJarClassLoader loader;

    private UltiToolsPlugin txPlugin;
    private UltiToolsPlugin ordinaryPlugin;
    private Object orderService;
    private Object noteService;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        org.mockbukkit.mockbukkit.MockBukkit.mock();

        // Both stores are built before the static mock is opened, as the other JSON wiring tests
        // build theirs: JsonStore's constructor tries to start the JVM-wide flush scheduler through
        // UltiTools.getInstance(), and must not target the mock plugin. Building a store reads
        // nothing from disk; recovery and table loading run at the first operator request for an
        // identity. So restartedStore, used only after the first store is discarded, is the same
        // as a store opened by a server restart over the same folder.
        String storeLocation = new File(tempDir, "json-store").getPath();
        store = new JsonStore(storeLocation);
        restartedStore = new JsonStore(storeLocation);

        DependenceManagers dependenceManagers = mock(DependenceManagers.class);
        lenient().when(dependenceManagers.getContext()).thenReturn(new SimpleContainer());
        ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.getDataFolder()).thenReturn(tempDir);
        lenient().when(ultiTools.getLogger()).thenReturn(Logger.getLogger("JsonTransactionalModuleTest"));
        lenient().when(ultiTools.getDataStore()).thenReturn(store);
        lenient().when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
        lenient().when(ultiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
        lenient().when(ultiTools.getCommandManager()).thenReturn(mock(CommandManager.class));
        lenient().when(ultiTools.getListenerManager()).thenReturn(mock(ListenerManager.class));
        // UltiToolsPlugin's no-arg constructor reads getConfig().getString("language").
        lenient().when(ultiTools.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());

        Map<String, File> jars = new HashMap<>();
        jars.put(TX_MODULE, buildModuleJar(TX_PLUGIN_NAME, Class.forName(TX_MODULE)));
        jars.put(ORDINARY_MODULE, buildModuleJar(ORDINARY_PLUGIN_NAME, Class.forName(ORDINARY_MODULE)));
        loader = new FixtureJarClassLoader(jars, Thread.currentThread().getContextClassLoader(), FIXTURE_ROOT);

        ultiToolsStatic = mockStatic(UltiTools.class, CALLS_REAL_METHODS);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);
        ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);
        // The module entity scan loads each JAR entry through the framework's plugin class loader;
        // on a server that loader holds the module JARs, here the fixture loader does.
        ultiToolsStatic.when(UltiTools::getJavaPluginClassLoader).thenReturn(loader);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsStatic != null) {
            ultiToolsStatic.close();
        }
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    // ===== Tests =====

    @Test
    @DisplayName("Both modules register on JSON; the transactional service is a proxy bound to the JSON transaction manager")
    void bothModulesRegister() throws Throwable {
        loadBothModules();

        assertThat(ProxyFactory.isProxyClass(orderService.getClass()))
                .as("the @Transactional service must be created as an AOP proxy, but it is a plain %s",
                        orderService.getClass().getName())
                .isTrue();
        assertThat(txPlugin.getContext().getBean(TransactionManager.class))
                .as("the transaction manager the module's container resolves")
                .isInstanceOf(JsonTransactionManager.class);
        assertOrdinaryModuleWorks("n-register");
    }

    @Test
    @DisplayName("The committing @Transactional method shows both writes")
    void commitShowsBothWrites() throws Throwable {
        loadBothModules();

        placeOrder("o1", "sword", () -> { });

        assertThat(orderItem("o1")).as("the order table after commit").isEqualTo("sword");
        assertThat(ledgerEntry("o1")).as("the ledger table after commit").isEqualTo("charged sword");
        assertOrdinaryModuleWorks("n-commit");
    }

    @Test
    @DisplayName("The throwing @Transactional method shows neither write")
    void throwShowsNeither() throws Throwable {
        loadBothModules();

        Throwable thrown = catchThrowable(() -> call(orderService, "placeOrderThenFail",
                new Class<?>[]{String.class, String.class}, "o2", "shield"));

        assertThat(thrown).as("the method's own failure reaches the caller")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payment refused for o2");
        assertThat(orderItem("o2")).as("the order table after rollback").isNull();
        assertThat(ledgerEntry("o2")).as("the ledger table after rollback").isNull();
        assertOrdinaryModuleWorks("n-rollback");
    }

    @Test
    // PMD.AvoidCatchingThrowable: the worker hands any failure, an assertion error included, back
    // to the test thread, which asserts it is absent.
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @DisplayName("While the committing method waits between its two writes, another thread sees neither; after commit it sees both")
    void anotherThreadNeverSeesAHalf() throws Throwable {
        loadBothModules();
        CountDownLatch firstWriteDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                placeOrder("o3", "bow", () -> {
                    firstWriteDone.countDown();
                    awaitOrFail(release, "the test releases the paused transaction");
                });
            } catch (Throwable t) {
                workerFailure.set(t);
            }
        }, "json-tx-module-writer");
        worker.setDaemon(true);
        worker.start();
        try {
            assertThat(firstWriteDone.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS))
                    .as("the transactional method reaches the point between its two writes").isTrue();

            assertThat(orderItem("o3")).as("the order table, read by another thread mid-transaction").isNull();
            assertThat(ledgerEntry("o3")).as("the ledger table, read by another thread mid-transaction").isNull();
            assertOrdinaryModuleWorks("n-during");
        } finally {
            release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        }

        assertThat(worker.isAlive()).as("the transactional method finished").isFalse();
        assertThat(workerFailure.get()).as("the transactional method's failure, if any").isNull();
        assertThat(orderItem("o3")).as("the order table after commit").isEqualTo("bow");
        assertThat(ledgerEntry("o3")).as("the ledger table after commit").isEqualTo("charged bow");
    }

    @Test
    @SuppressWarnings("unchecked") // the fixture entity is reached by reflection
    @DisplayName("After the store is discarded without a flush and a new one opens the same folder, the committed writes are present and the rolled-back ones absent")
    void committedResultSurvivesDiscardAndReopen() throws Throwable {
        loadBothModules();
        placeOrder("o4", "lantern", () -> { });
        assertThat(catchThrowable(() -> call(orderService, "placeOrderThenFail",
                new Class<?>[]{String.class, String.class}, "o5", "rope")))
                .isInstanceOf(IllegalStateException.class);

        // The first store is dropped here: nothing flushes it, as a server stop by a crash or a
        // kill does not. Every later data request of both modules reaches the restarted store.
        lenient().when(ultiTools.getDataStore()).thenReturn(restartedStore);

        DataOperator<BaseDataEntity<String>> orders = operatorOf(txPlugin, TX_MODULE + "$OrderRow");
        DataOperator<BaseDataEntity<String>> ledger = operatorOf(txPlugin, TX_MODULE + "$LedgerRow");
        assertThat(orders).as("the restarted store's operator").isNotSameAs(operatorField(orderService, "orders"));
        assertThat(property(orders.getById("o4"), "getItem")).as("the committed order after restart")
                .isEqualTo("lantern");
        assertThat(property(ledger.getById("o4"), "getEntry")).as("the committed ledger entry after restart")
                .isEqualTo("charged lantern");
        assertThat(orders.getById("o5")).as("the rolled-back order after restart").isNull();
        assertThat(ledger.getById("o5")).as("the rolled-back ledger entry after restart").isNull();

        DataOperator<BaseDataEntity<String>> notes = operatorOf(ordinaryPlugin, ORDINARY_MODULE + "$NoteRow");
        BaseDataEntity<String> note = (BaseDataEntity<String>) loader.loadClass(ORDINARY_MODULE + "$NoteRow")
                .getConstructor(String.class, String.class).newInstance("n-restart", "after restart");
        notes.insert(note);
        assertThat(property(notes.getById("n-restart"), "getText"))
                .as("the ordinary module on the restarted store").isEqualTo("after restart");
    }

    // ===== Module loading =====

    private void loadBothModules() throws Exception {
        PluginManager pluginManager = new PluginManager();
        Field classLoaderField = PluginManager.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        classLoaderField.set(pluginManager, loader);

        txPlugin = register(pluginManager, TX_MODULE);
        ordinaryPlugin = register(pluginManager, ORDINARY_MODULE);
        orderService = txPlugin.getContext().getBean(loader.loadClass(TX_MODULE + "$OrderService"));
        noteService = ordinaryPlugin.getContext().getBean(loader.loadClass(ORDINARY_MODULE + "$NoteService"));
        assertNotNull(orderService, "the transactional module's service");
        assertNotNull(noteService, "the ordinary module's service");
    }

    @SuppressWarnings("unchecked")
    private UltiToolsPlugin register(PluginManager pluginManager, String moduleClassName) throws Exception {
        Class<? extends UltiToolsPlugin> pluginClass =
                (Class<? extends UltiToolsPlugin>) Class.forName(moduleClassName, true, loader);
        UltiToolsPlugin plugin = pluginClass.getDeclaredConstructor().newInstance();
        assertThat(pluginManager.register(plugin)).as("register(%s) on JSON", moduleClassName).isTrue();
        return plugin;
    }

    // ===== Calls into the fixture modules (loaded by the fixture loader, reached by reflection) =====

    private void placeOrder(String id, String item, Runnable betweenWrites) throws Throwable {
        call(orderService, "placeOrder", new Class<?>[]{String.class, String.class, Runnable.class},
                id, item, betweenWrites);
    }

    private String orderItem(String id) throws Throwable {
        return (String) call(orderService, "orderItem", new Class<?>[]{String.class}, id);
    }

    private String ledgerEntry(String id) throws Throwable {
        return (String) call(orderService, "ledgerEntry", new Class<?>[]{String.class}, id);
    }

    /** The ordinary module inserts a note outside any transaction and reads it back. */
    private void assertOrdinaryModuleWorks(String id) throws Throwable {
        call(noteService, "write", new Class<?>[]{String.class, String.class}, id, "text of " + id);
        assertThat(call(noteService, "read", new Class<?>[]{String.class}, id))
                .as("the ordinary module reads back its own insert").isEqualTo("text of " + id);
    }

    /**
     * Invokes {@code name} as declared on the fixture class (the bean may be its proxy subclass),
     * unwrapping the method's own failure.
     */
    private static Object call(Object bean, String name, Class<?>[] types, Object... args) throws Throwable {
        Class<?> declared = bean.getClass();
        while (ProxyFactory.isProxyClass(declared)) {
            declared = declared.getSuperclass();
        }
        Method method = declared.getMethod(name, types);
        try {
            return method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DataOperator<BaseDataEntity<String>> operatorOf(UltiToolsPlugin plugin, String entityClassName) throws Exception {
        Class entity = loader.loadClass(entityClassName);
        return (DataOperator<BaseDataEntity<String>>) plugin.getDataOperator(entity);
    }

    private static Object operatorField(Object bean, String name) throws Exception {
        Class<?> declared = bean.getClass();
        while (ProxyFactory.isProxyClass(declared)) {
            declared = declared.getSuperclass();
        }
        Field field = declared.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bean);
    }

    private static Object property(Object entity, String getter) throws Exception {
        assertThat(entity).as("the entity read back").isNotNull();
        return entity.getClass().getMethod(getter).invoke(entity);
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting until " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting until " + what, e);
        }
    }

    // ===== Fixture JARs =====

    /**
     * Writes a module JAR holding {@code moduleClass}, every class nested in it, and a
     * {@code plugin.yml} naming {@code pluginName}.
     */
    private File buildModuleJar(String pluginName, Class<?> moduleClass) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        classes.add(moduleClass);
        classes.addAll(Arrays.asList(moduleClass.getDeclaredClasses()));
        File jar = new File(tempDir, pluginName + ".jar");
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
            output.write(("name: " + pluginName + "\nversion: 1.0.0\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    /**
     * Child-first for the fixture packages: their classes come from the module JARs, so each
     * module class's {@code CodeSource} is its own JAR. A resource lookup for a module's package
     * directory answers with that module's JAR, so the component scan reads the JAR.
     */
    static final class FixtureJarClassLoader extends URLClassLoader {
        private final String isolatedPrefix;
        private final Map<String, URL> packageRoots = new HashMap<>();

        FixtureJarClassLoader(Map<String, File> jarsByModule, ClassLoader parent, String isolatedPrefix)
                throws MalformedURLException {
            super(toUrls(jarsByModule), parent);
            this.isolatedPrefix = isolatedPrefix;
            for (Map.Entry<String, File> module : jarsByModule.entrySet()) {
                String moduleClass = module.getKey();
                String packagePath = moduleClass.substring(0, moduleClass.lastIndexOf('.')).replace('.', '/');
                packageRoots.put(packagePath, new URL("jar:" + module.getValue().toURI().toURL() + "!/"));
            }
        }

        private static URL[] toUrls(Map<String, File> jarsByModule) throws MalformedURLException {
            List<URL> urls = new ArrayList<>();
            for (File jar : jarsByModule.values()) {
                urls.add(jar.toURI().toURL());
            }
            return urls.toArray(new URL[0]);
        }

        @Override
        public URL getResource(String name) {
            // Only a package directory itself: a class-file lookup (ByteBuddy reads class files
            // through getResource) must still find the real entry.
            String directory = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            URL root = packageRoots.get(directory);
            return root != null ? root : super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && name.startsWith(isolatedPrefix)) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Not in the module JARs: normal parent-first delegation below.
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
}

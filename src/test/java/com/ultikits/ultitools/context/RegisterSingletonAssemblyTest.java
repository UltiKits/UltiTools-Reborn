package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.registersingletonordering.OrderingFixtureModule;
import com.ultikits.testfixtures.registersingletonordering.OrderingFixtureService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ExceptionInterceptor;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.DependenceManagers;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Pins D-14 (full assembly, unconditional on {@code refresh()} state) and D-15 (refusal of an
 * AOP-annotated pre-constructed instance) for {@link SimpleContainer#registerSingleton}.
 * <p>
 * Before this plan, {@code registerSingleton} was a two-line method: {@code addSingleton} plus a
 * type-mapping write. No autowiring, no {@code @PostConstruct}, no {@code BeanPostProcessor}
 * chain -- and no refusal for an object whose class carries an AOP annotation it could never
 * honour. Every assembly case below is run <b>both</b> before and after {@code refresh()}, because
 * "the outcome does not depend on refresh state" is the substance of D-14 and a single-state test
 * cannot show it -- a post-refresh-only case would also pass under a narrower window-guard fix
 * that D-14 explicitly rejects as insufficient (it would have left the config entities, the
 * {@code @Configuration} instance, and the {@code @Bean} products -- all registered before
 * {@code refresh()} -- exactly as uninjected as before).
 */
@DisplayName("registerSingleton full assembly and AOP refusal (D-14/D-15)")
class RegisterSingletonAssemblyTest {

    // ===== Fixtures =====

    static class Dependency {
    }

    static class WithAutowiredField {
        @Autowired
        Dependency dependency;
    }

    static class WithPostConstruct {
        int callCount = 0;

        @PostConstruct
        void init() {
            callCount++;
        }
    }

    static class Plain {
        String value = "unchanged";
    }

    /** Substitutes the bean with a different instance in postProcessAfterInitialization. */
    static class SubstitutingPostProcessor implements BeanPostProcessor {
        final Object replacement;

        SubstitutingPostProcessor(Object replacement) {
            this.replacement = replacement;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return replacement;
        }
    }

    /** Records every bean name/instance it sees, for both processor callbacks. */
    static class RecordingPostProcessor implements BeanPostProcessor {
        java.util.List<String> beforeNames = new java.util.ArrayList<>();
        java.util.List<String> afterNames = new java.util.ArrayList<>();

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            beforeNames.add(beanName);
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            afterNames.add(beanName);
            return bean;
        }
    }

    // ===== Task 1: full assembly, unconditional on refresh() state (D-14) =====

    @Nested
    @DisplayName("@Autowired field is populated regardless of refresh() state")
    class AutowiringAssembly {

        @Test
        @DisplayName("before refresh(): field is populated")
        void populatedBeforeRefresh() {
            SimpleContainer container = new SimpleContainer();
            Dependency dependency = new Dependency();
            container.registerSingleton("dependency", dependency);

            WithAutowiredField bean = new WithAutowiredField();
            container.registerSingleton("withAutowiredField", bean);

            assertSame(dependency, bean.dependency,
                    "the @Autowired field must be populated even though refresh() has not run yet");
        }

        @Test
        @DisplayName("after refresh(): field is populated too -- same outcome, not gated on refresh state")
        void populatedAfterRefresh() {
            SimpleContainer container = new SimpleContainer();
            Dependency dependency = new Dependency();
            container.registerSingleton("dependency", dependency);
            container.refresh();

            WithAutowiredField bean = new WithAutowiredField();
            container.registerSingleton("withAutowiredField", bean);

            assertSame(dependency, bean.dependency,
                    "the @Autowired field must be populated after refresh() has already run -- "
                            + "D-14 explicitly rejects gating assembly on refresh state");
        }
    }

    @Nested
    @DisplayName("@PostConstruct is invoked exactly once, regardless of refresh() state")
    class PostConstructAssembly {

        @Test
        @DisplayName("before refresh(): @PostConstruct runs once")
        void invokedBeforeRefresh() {
            SimpleContainer container = new SimpleContainer();
            WithPostConstruct bean = new WithPostConstruct();

            container.registerSingleton("withPostConstruct", bean);

            assertEquals(1, bean.callCount,
                    "@PostConstruct must run exactly once as a side effect of registerSingleton, "
                            + "not merely leave the bean retrievable afterwards");
        }

        @Test
        @DisplayName("after refresh(): @PostConstruct runs once too")
        void invokedAfterRefresh() {
            SimpleContainer container = new SimpleContainer();
            container.refresh();
            WithPostConstruct bean = new WithPostConstruct();

            container.registerSingleton("withPostConstruct", bean);

            assertEquals(1, bean.callCount, "@PostConstruct must run once after refresh() too");
        }

        @Test
        @DisplayName("registering a second bean under the same name does not re-invoke the first's @PostConstruct")
        void secondRegistrationUnderSameNameDoesNotDoubleInvokeFirst() {
            SimpleContainer container = new SimpleContainer();
            WithPostConstruct first = new WithPostConstruct();
            container.registerSingleton("shared", first);
            assertEquals(1, first.callCount);

            WithPostConstruct second = new WithPostConstruct();
            container.registerSingleton("shared", second);

            assertEquals(1, first.callCount,
                    "the first object's own @PostConstruct must not run again just because a "
                            + "second object was registered under the same name");
            assertEquals(1, second.callCount, "the second object's own @PostConstruct must run exactly once");
        }
    }

    @Nested
    @DisplayName("BeanPostProcessor chain sees the instance both before and after initialization")
    class BeanPostProcessorChain {

        @Test
        @DisplayName("both callbacks are invoked with the registered name")
        void bothCallbacksInvoked() {
            SimpleContainer container = new SimpleContainer();
            RecordingPostProcessor processor = new RecordingPostProcessor();
            container.addBeanPostProcessor(processor);

            container.registerSingleton("plain", new Plain());

            assertEquals(java.util.Collections.singletonList("plain"), processor.beforeNames);
            assertEquals(java.util.Collections.singletonList("plain"), processor.afterNames);
        }

        @Test
        @DisplayName("a substitute returned from postProcessAfterInitialization is what getBean returns")
        void substituteIsStored() {
            SimpleContainer container = new SimpleContainer();
            Plain replacement = new Plain();
            replacement.value = "substituted";
            container.addBeanPostProcessor(new SubstitutingPostProcessor(replacement));

            container.registerSingleton("plain", new Plain());

            assertSame(replacement, container.getBean("plain"),
                    "getBean must return whatever postProcessAfterInitialization returned, not the "
                            + "original argument -- otherwise every processor sees the bean and none "
                            + "of them can actually affect it");
        }
    }

    @Nested
    @DisplayName("an object with nothing to assemble behaves exactly as before")
    class NoOpAssembly {

        @Test
        @DisplayName("stored and retrievable, unchanged")
        void storedAndRetrievable() {
            SimpleContainer container = new SimpleContainer();
            Plain plain = new Plain();

            container.registerSingleton("plain", plain);

            assertSame(plain, container.getBean("plain"));
            assertEquals("unchanged", plain.value);
        }
    }

    // ===== Task 2: AOP refusal on a pre-constructed instance (D-15) =====

    static class MethodLevelExceptionCatchBean {
        @ExceptionCatch
        void guarded() {
        }
    }

    static class MethodLevelTransactionalBean {
        @Transactional
        void guarded() {
        }
    }

    @Transactional
    static class ClassLevelTransactionalBean {
        void work() {
        }
    }

    @ExceptionCatch
    static class ClassLevelExceptionCatchBean {
        void work() {
        }
    }

    static class NoAopAnnotationBean {
        void work() {
        }
    }

    @Nested
    @DisplayName("refuses an instance whose class carries an AOP annotation it can never honour")
    class AopRefusal {

        @Test
        @DisplayName("method-level @ExceptionCatch is refused, naming the class and the method")
        void methodLevelExceptionCatchRefused() {
            SimpleContainer container = new SimpleContainer();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.registerSingleton("guarded", new MethodLevelExceptionCatchBean()));

            assertTrue(thrown.getMessage().contains(MethodLevelExceptionCatchBean.class.getName()),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains("guarded"), thrown.getMessage());
        }

        @Test
        @DisplayName("method-level @Transactional is refused too")
        void methodLevelTransactionalRefused() {
            SimpleContainer container = new SimpleContainer();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.registerSingleton("guarded", new MethodLevelTransactionalBean()));

            assertTrue(thrown.getMessage().contains(MethodLevelTransactionalBean.class.getName()),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("class-level @Transactional is refused -- not just method-level")
        void classLevelTransactionalRefused() {
            SimpleContainer container = new SimpleContainer();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.registerSingleton("classLevel", new ClassLevelTransactionalBean()));

            assertTrue(thrown.getMessage().contains(ClassLevelTransactionalBean.class.getName()),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("class-level @ExceptionCatch is refused too")
        void classLevelExceptionCatchRefused() {
            SimpleContainer container = new SimpleContainer();

            ContainerException thrown = assertThrows(ContainerException.class,
                    () -> container.registerSingleton("classLevel", new ClassLevelExceptionCatchBean()));

            assertTrue(thrown.getMessage().contains(ClassLevelExceptionCatchBean.class.getName()),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("an instance with no AOP annotation is never refused")
        void noAnnotationNotRefused() {
            SimpleContainer container = new SimpleContainer();

            assertDoesNotThrow(() -> container.registerSingleton("plain", new NoAopAnnotationBean()));
        }

        @Test
        @DisplayName("a generated proxy is not refused, even though its class carries the copied annotation")
        void generatedProxyNotRefused() {
            SimpleContainer aopContainer = new SimpleContainer();
            AopProxyResolver resolver = new AopProxyResolver();
            resolver.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class, new ExceptionInterceptor(), 200));
            aopContainer.setAopProxyResolver(resolver);
            aopContainer.registerBean(MethodLevelExceptionCatchBean.class);
            aopContainer.refresh();

            MethodLevelExceptionCatchBean proxied = aopContainer.getBean(MethodLevelExceptionCatchBean.class);
            assertTrue(ProxyFactory.isProxyClass(proxied.getClass()),
                    "guard: this case must actually exercise a real generated proxy, or refusing "
                            + "it (or not) proves nothing");

            SimpleContainer target = new SimpleContainer();
            assertDoesNotThrow(() -> target.registerSingleton("proxied", proxied),
                    "a generated proxy already honours its own AOP annotations and must not be refused");
        }
    }

    @Nested
    @DisplayName("the framework's own bootstrap registrations must still succeed")
    class BootstrapCompatibility {

        @Test
        @DisplayName("a plain object with neither @Autowired, @PostConstruct, nor an AOP annotation still loads")
        void plainBootstrapObjectStillLoads() {
            SimpleContainer container = new SimpleContainer();

            assertDoesNotThrow(() -> container.registerSingleton("bootstrap", new NoAopAnnotationBean()));
            assertNotNull(container.getBean("bootstrap"));
        }
    }

    // ===== Task 2: initializePlugin ordering (T-03-27) =====

    @Nested
    @DisplayName("PluginManager.initializePlugin: the plugin's own @Autowired field resolves "
            + "against its own scanned beans")
    class InitializePluginOrdering {

        @TempDir
        File tempDir;

        @org.junit.jupiter.api.BeforeEach
        void setUpBukkit() {
            // scanEntitiesInJar's own IOException/LinkageError/RuntimeException catch (D-19)
            // reaches Bukkit.getLogger() when this fixture's untrusted-package SecurityException
            // is logged; that call NPEs without a real Bukkit server present.
            com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
            org.mockbukkit.mockbukkit.MockBukkit.mock();
        }

        @org.junit.jupiter.api.AfterEach
        void tearDownBukkit() {
            com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
        }

        /**
         * Child-first for the fixture package only. {@link OrderingFixtureModule} and
         * {@link OrderingFixtureService} are also compiled normally onto the ambient test
         * classpath (so {@code ComponentScanner}'s directory-based package scan can discover
         * them there via ordinary parent-first {@code getResource}), but {@code
         * UltiToolsPlugin}'s no-arg constructor needs the plugin class's own {@code CodeSource}
         * to be a real jar file, which {@code target/test-classes} is not. Trying this loader's
         * own jar FIRST for the fixture package (instead of standard parent-first delegation) is
         * what gives {@code OrderingFixtureModule} a jar-backed {@code CodeSource}; every other
         * class (supertypes, annotations) still delegates to the parent normally.
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
            File jar = new File(tempDir, "ordering-fixture.jar");
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
        @DisplayName("the module main class's @Autowired field is populated after initializePlugin returns")
        void autowiredFieldOnModuleMainClassIsPopulated() throws Exception {
            File jar = buildFixtureJar();
            FixtureJarClassLoader loader = new FixtureJarClassLoader(jar.toURI().toURL(),
                    Thread.currentThread().getContextClassLoader(),
                    "com.ultikits.testfixtures.registersingletonordering.");
            Class<?> pluginClass = Class.forName(
                    "com.ultikits.testfixtures.registersingletonordering.OrderingFixtureModule",
                    true, loader);

            DependenceManagers mockDependenceManagers = mock(DependenceManagers.class);
            when(mockDependenceManagers.getContext()).thenReturn(new SimpleContainer());

            JsonStore jsonStore = new JsonStore(new File(tempDir, "json").getAbsolutePath());

            UltiTools mockUltiTools = mock(UltiTools.class);
            lenient().when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
            lenient().when(mockUltiTools.getLogger()).thenReturn(
                    Logger.getLogger("RegisterSingletonAssemblyTest.InitializePluginOrdering"));
            lenient().when(mockUltiTools.getDataStore()).thenReturn(jsonStore);
            lenient().when(mockUltiTools.getDependenceManagers()).thenReturn(mockDependenceManagers);
            lenient().when(mockUltiTools.getConfigManager()).thenReturn(mock(ConfigManager.class));
            // UltiToolsPlugin's no-arg constructor reads getConfig().getString("language")
            // (Bukkit's own JavaPlugin.getConfig(), unstubbed on a full mock returns null and
            // NPEs) -- an empty YamlConfiguration is enough; a missing "language" key just falls
            // through to the {} default language.
            lenient().when(mockUltiTools.getConfig())
                    .thenReturn(new org.bukkit.configuration.file.YamlConfiguration());

            PluginManager pluginManager = new PluginManager();
            Method initializePlugin = PluginManager.class.getDeclaredMethod(
                    "initializePlugin", ClassLoader.class, Class.class, Object[].class);
            initializePlugin.setAccessible(true);

            Object plugin;
            // passesCompatibilityGates -> UltiTools.getPluginVersion() -> getEnv() reads env.yml
            // through getInstance().getTextResource(String), which is protected on Bukkit's own
            // JavaPlugin and cannot be stubbed from this package -- so the STATIC call site is
            // stubbed directly instead, the same workaround PluginManagerTest's own
            // CompatibilityGateOrderingTests uses for the identical problem.
            try (org.mockito.MockedStatic<UltiTools> ultiToolsStatic = org.mockito.Mockito.mockStatic(
                    UltiTools.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                ultiToolsStatic.when(UltiTools::getPluginVersion).thenReturn(Integer.MAX_VALUE);

                plugin = initializePlugin.invoke(pluginManager, loader, pluginClass, new Object[0]);
            }

            assertNotNull(plugin, "initializePlugin must not refuse this module");
            Field serviceField = pluginClass.getDeclaredField("service");
            serviceField.setAccessible(true);
            assertNotNull(serviceField.get(plugin),
                    "the module main class's own @Autowired field must be populated -- inert "
                            + "case: asserting only that initializePlugin returned non-null passes "
                            + "even if the field is null, which is the pre-6.3.0 defect this "
                            + "milestone exists to delete");
        }
    }
}

package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.missingdependency.CommandExecutorWithMissingDependency;
import com.ultikits.testfixtures.missingdependency.HasMethodReferencingMissingType;
import com.ultikits.testfixtures.missingdependency.MissingDependencyType;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;

import org.bukkit.command.CommandExecutor;

/**
 * 07-21 Task 2: proves {@code SimpleContainer.createBean}/{@code preInstantiateSingletons} skip a
 * bean whose class references a symbol absent from the classpath -- instead of letting the whole
 * container's {@code refresh()} (and therefore the whole plugin module) abort on a bare,
 * uncaught {@link NoClassDefFoundError}, matching the real-server evidence in
 * {@code 07-UAT-CRITERION-1.md}: {@code AopProxyResolver.resolve -> ReflectionUtil.getAllMethods}
 * eagerly resolves every method's parameter/return type, throwing at bean-creation time rather
 * than at construction.
 * <p>
 * Reuses {@code FinalContractValidatorTest}'s {@code BlockingClassLoader} fixture technique --
 * and its exact fixture pair, {@link HasMethodReferencingMissingType} /
 * {@link MissingDependencyType} -- per this task's own {@code read_first} guidance, so the
 * poisoned bean's {@link NoClassDefFoundError} is genuine, not mocked.
 * <br>
 * 07-21 任务 2：证明当某个 Bean 的类引用了类路径中不存在的符号时，
 * {@code SimpleContainer.createBean}/{@code preInstantiateSingletons} 会跳过该 Bean——
 * 而不是让整个容器的 {@code refresh()}（进而整个插件模块）因一个未捕获的裸
 * {@link NoClassDefFoundError} 而中止。
 */
@DisplayName("SimpleContainer LinkageError resilience (07-21)")
class SimpleContainerLinkageResilienceTest {

    private final List<LogRecord> diagnosticsCaptured = new ArrayList<>();
    private Logger diagnosticsLogger;
    private Handler diagnosticsHandler;

    /** A healthy fixture bean with no reference to any missing type. */
    public static class SurvivorBean {
        public String ping() {
            return "pong";
        }
    }


    @BeforeEach
    void captureDiagnostics() {
        diagnosticsCaptured.clear();
        diagnosticsLogger = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        diagnosticsLogger.setLevel(Level.ALL);
        diagnosticsHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                diagnosticsCaptured.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        diagnosticsHandler.setLevel(Level.ALL);
        diagnosticsLogger.addHandler(diagnosticsHandler);
    }

    @AfterEach
    void releaseDiagnostics() {
        diagnosticsLogger.removeHandler(diagnosticsHandler);
    }

    // Copied technique, not copied class: mirrors FinalContractValidatorTest's own
    // BlockingClassLoader exactly (see that test's class-level comment for the full rationale --
    // a bootstrap-parented loader would also cut off com.ultikits.ultitools.annotations.* and
    // friends). The parent here is this test's own (normal) class loader, so every ordinary type
    // resolves exactly as it does anywhere else in the JVM; only the blocked name plus the
    // self-defined name(s) are treated specially.
    // 07-23 Task 2: widened from a single selfDefinedClassName to a varargs set of names --
    // required so a SECOND fixture class (CommandExecutorWithMissingDependency) can be defined
    // by the SAME loader instance as HasMethodReferencingMissingType, which is what makes
    // Field.getType() on the new fixture's @Autowired field return the EXACT SAME Class object
    // this test registers in the container as the poisoned bean's own definition. Two Class
    // objects for textually-identical bytecode loaded by two DIFFERENT class loaders are never
    // .equals()/mutually assignable in the JVM's eyes.
    private static final class BlockingClassLoader extends URLClassLoader {
        private final String blockedClassName;
        private final List<String> selfDefinedClassNames;

        BlockingClassLoader(URL[] urls, ClassLoader parent, String blockedClassName,
                String... selfDefinedClassNames) {
            super(urls, parent);
            this.blockedClassName = blockedClassName;
            this.selfDefinedClassNames = java.util.Arrays.asList(selfDefinedClassNames);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (blockedClassName.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            if (!selfDefinedClassNames.contains(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private BlockingClassLoader newLoaderHiding(Class<?> hiddenType, Class<?>... targetTypes) {
        URL classesRoot = targetTypes[0].getProtectionDomain().getCodeSource().getLocation();
        String[] targetNames = new String[targetTypes.length];
        for (int i = 0; i < targetTypes.length; i++) {
            targetNames[i] = targetTypes[i].getName();
        }
        return new BlockingClassLoader(new URL[]{classesRoot},
                SimpleContainerLinkageResilienceTest.class.getClassLoader(),
                hiddenType.getName(), targetNames);
    }

    @Test
    @DisplayName("Sanity: the poisoned fixture genuinely throws NoClassDefFoundError from "
            + "getDeclaredMethods")
    void sanityPoisonedClassGenuinelyThrows() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            assertThat(poisonedClass.getClassLoader()).as("fixture must actually be defined by "
                    + "the blocking loader, or its block is a no-op").isSameAs(loader);
            assertThatThrownBy(poisonedClass::getDeclaredMethods)
                    .as("fixture must actually throw NoClassDefFoundError for the tests below to "
                            + "mean anything")
                    .isInstanceOf(NoClassDefFoundError.class);
        }
    }

    @Test
    @DisplayName("A bean whose class references a removed symbol is skipped; the survivor bean "
            + "still gets created")
    void poisonedBeanIsSkippedSurvivorStillCreated() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("test-module");
            container.registerBean(poisonedClass);
            container.registerBean(SurvivorBean.class);

            assertThatCode(container::refresh)
                    .as("no exception may escape refresh() -- the poisoned bean must be skipped, "
                            + "not left to abort the whole container")
                    .doesNotThrowAnyException();

            Object survivor = container.getBean("survivorBean");
            assertThat(survivor).isNotNull();
            assertThat(((SurvivorBean) survivor).ping()).isEqualTo("pong");

            boolean poisonedInstancePresent = container.getSingletonValues().stream()
                    .anyMatch(bean -> bean.getClass().getName().equals(poisonedClass.getName()));
            assertThat(poisonedInstancePresent)
                    .as("the poisoned bean must not have been instantiated as a singleton")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The skipped bean is recorded via ModuleScanDiagnostics as one named SEVERE "
            + "summary")
    void skippedBeanIsRecordedViaModuleScanDiagnostics() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("test-module");
            container.registerBean(poisonedClass);
            container.registerBean(SurvivorBean.class);

            container.refresh();
        }

        List<LogRecord> severe = new ArrayList<>();
        for (LogRecord record : diagnosticsCaptured) {
            if (Level.SEVERE.equals(record.getLevel())) {
                severe.add(record);
            }
        }

        assertThat(severe).hasSize(1);
        String message = severe.get(0).getMessage();
        assertThat(message)
                .contains("test-module")
                .contains(HasMethodReferencingMissingType.class.getName())
                .contains("COMPATIBILITY.md");
    }

    @Test
    @DisplayName("A container with only healthy beans produces zero ModuleScanDiagnostics "
            + "records")
    void healthyContainerEmitsNoDiagnosticsRecord() {
        SimpleContainer container = new SimpleContainer();
        container.setAopProxyResolver(new AopProxyResolver());
        container.setDisplayName("test-module");
        container.registerBean(SurvivorBean.class);

        container.refresh();

        assertThat(diagnosticsCaptured).isEmpty();
    }

    @Test
    @DisplayName("A container that never calls setDisplayName still does not throw when a bean "
            + "is poisoned")
    void poisonedBeanWithoutDisplayNameStillDoesNotThrow() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            // Deliberately no setDisplayName call -- ModuleScanDiagnostics' own null/blank
            // identifier tolerance must absorb it silently rather than this task inventing a
            // fallback string.
            container.registerBean(poisonedClass);
            container.registerBean(SurvivorBean.class);

            assertThatCode(container::refresh).doesNotThrowAnyException();

            assertThat(container.getBean("survivorBean")).isNotNull();
            assertThat(diagnosticsCaptured).isEmpty();
        }
    }

    // === 07-23 Task 1: SimpleContainer remembers a failed bean =====================

    @Test
    @DisplayName("A second, independent getBean() call for an already-failed bean rethrows the "
            + "original exception type -- never null -- and its ModuleScanDiagnostics record "
            + "still names the class exactly once, not once per encounter")
    void secondIndependentGetBeanCallRethrowsOriginalTypeWithoutDuplicateDiagnostics()
            throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("test-module");
            container.registerBean(poisonedClass);
            container.registerBean(SurvivorBean.class);

            assertThatCode(container::refresh).doesNotThrowAnyException();

            // Simulates a second, independent caller resolving the same bean by name after
            // preInstantiateSingletons already tried and failed it once (the exact shape
            // PluginManager.validateCommandExecutorContracts's second getBean() call has).
            assertThatThrownBy(() -> container.getBean("hasMethodReferencingMissingType"))
                    .as("a repeat direct lookup must fail fast with the ORIGINAL exception type, "
                            + "never null and never a different type")
                    .isInstanceOf(NoClassDefFoundError.class);

            List<LogRecord> severe = new ArrayList<>();
            for (LogRecord record : diagnosticsCaptured) {
                if (Level.SEVERE.equals(record.getLevel())) {
                    severe.add(record);
                }
            }
            assertThat(severe)
                    .as("only refresh()'s own preInstantiateSingletons emits a summary; the "
                            + "second direct getBean() call must not trigger a second one")
                    .hasSize(1);
            String message = severe.get(0).getMessage();
            int occurrences = message.split(
                    java.util.regex.Pattern.quote(HasMethodReferencingMissingType.class.getName()),
                    -1).length - 1;
            assertThat(occurrences)
                    .as("the poisoned class must be named exactly once in the summary, not once "
                            + "per encounter -- closing the duplicate-listing artifact "
                            + "07-22-SUMMARY.md observed on the real server")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("getBeanNamesForType excludes an already-failed bean from a second, independent "
            + "enumeration call made strictly after refresh() returns -- the direct regression "
            + "test for PluginManager.validateCommandExecutorContracts's uncaught retry")
    void getBeanNamesForTypeExcludesMemoizedFailureAfterRefresh() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("test-module");
            container.registerBean(poisonedClass);
            container.registerBean(SurvivorBean.class);

            container.refresh();

            // Mirrors PluginManager.validateCommandExecutorContracts calling
            // getBeanNamesForType strictly AFTER refresh() returns, not during it.
            String[] names = container.getBeanNamesForType(Object.class);
            assertThat(names)
                    .as("a bean already skipped once by preInstantiateSingletons must not be "
                            + "re-offered by a later, independent enumeration call")
                    .doesNotContain("hasMethodReferencingMissingType")
                    .contains("survivorBean");

            // Mirrors validateCommandExecutorContracts's own per-name loop: resolve every
            // enumerated name and assert none of them throw.
            for (String name : names) {
                assertThatCode(() -> container.getBean(name))
                        .as("every name returned by getBeanNamesForType must resolve without "
                                + "throwing")
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("Memoization is instance-scoped, never static: a fresh SimpleContainer that "
            + "never touched the poisoned bean resolves a same-named healthy bean normally")
    void memoizationIsInstanceScopedNotStatic() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            SimpleContainer poisoned = new SimpleContainer();
            poisoned.setAopProxyResolver(new AopProxyResolver());
            poisoned.setDisplayName("poisoned-module");
            poisoned.registerBean(poisonedClass);
            assertThatCode(poisoned::refresh).doesNotThrowAnyException();
        }

        // A second, entirely separate container registers a HEALTHY bean under the exact same
        // bean name the first container's poisoned bean used -- a static/shared memo would make
        // this resolution fail too; an instance-scoped one must not.
        SimpleContainer fresh = new SimpleContainer();
        fresh.setAopProxyResolver(new AopProxyResolver());
        fresh.setDisplayName("fresh-module");
        fresh.registerBeanDefinition("hasMethodReferencingMissingType",
                new BeanDefinition(SurvivorBean.class));

        assertThatCode(fresh::refresh).doesNotThrowAnyException();
        Object bean = fresh.getBean("hasMethodReferencingMissingType");
        assertThat(bean).isNotNull().isInstanceOf(SurvivorBean.class);
    }

    @Test
    @DisplayName("close() clears the failed-bean memo: since close() is used as a terminal "
            + "operation in this codebase (never followed by reuse), this test uses a NEW "
            + "SimpleContainer instance after an earlier poisoned container was closed, and "
            + "confirms it is not preemptively affected by stale poison state")
    void closeClearsMemoNewContainerAfterCloseIsUnaffected() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class)) {
            Class<?> poisonedClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            SimpleContainer poisoned = new SimpleContainer();
            poisoned.setAopProxyResolver(new AopProxyResolver());
            poisoned.setDisplayName("poisoned-module");
            poisoned.registerBean(poisonedClass);
            assertThatCode(poisoned::refresh).doesNotThrowAnyException();
            poisoned.close();
        }

        SimpleContainer fresh = new SimpleContainer();
        fresh.setAopProxyResolver(new AopProxyResolver());
        fresh.setDisplayName("fresh-module");
        fresh.registerBeanDefinition("hasMethodReferencingMissingType",
                new BeanDefinition(SurvivorBean.class));

        assertThatCode(fresh::refresh).doesNotThrowAnyException();
        Object bean = fresh.getBean("hasMethodReferencingMissingType");
        assertThat(bean).isNotNull().isInstanceOf(SurvivorBean.class);
    }

    // === 07-23 Task 2: faithful CommandExecutor-shaped regression ===================

    /**
     * Test H's negative-control fixture: an {@code @Autowired} field of a type that is simply
     * never registered as any bean at all. No special class loader is involved -- this scenario
     * has nothing to do with classpath linkage.
     */
    static class NeverRegisteredDependency {
    }

    static class HasUnregisteredRequiredDependency {
        @Autowired
        private NeverRegisteredDependency dependency;
    }

    @Test
    @DisplayName("A CommandExecutor-shaped bean whose own class is clean but whose sole "
            + "@Autowired dependency is poisoned survives refresh() -- the module still loads, "
            + "matching 07-21's existing per-bean skip-and-continue and 07-22-SUMMARY.md's "
            + "measured BackupCommand/BackupService shape")
    void commandExecutorWithPoisonedTransitiveDependencySurvivesRefresh() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class,
                CommandExecutorWithMissingDependency.class)) {
            Class<?> poisonedServiceClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            Class<?> poisonedCommandClass =
                    loader.loadClass(CommandExecutorWithMissingDependency.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("backup-shaped-module");
            container.registerBean(poisonedServiceClass);
            container.registerBean(poisonedCommandClass);
            container.registerBean(SurvivorBean.class);

            assertThatCode(container::refresh)
                    .as("a CommandExecutor whose own class is clean but whose autowired "
                            + "dependency is poisoned must not abort the whole module")
                    .doesNotThrowAnyException();

            Object survivor = container.getBean("survivorBean");
            assertThat(survivor).isNotNull();
            assertThat(((SurvivorBean) survivor).ping()).isEqualTo("pong");
        }
    }

    @Test
    @DisplayName("getBeanNamesForType(CommandExecutor.class) excludes a bean whose sole "
            + "autowired dependency is poisoned from a second, independent enumeration call -- "
            + "matching PluginManager.validateCommandExecutorContracts's exact call shape, "
            + "including the two-argument getBean(name, type) overload it actually calls")
    void getBeanNamesForTypeExcludesTransitivelyPoisonedCommandExecutor() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class,
                CommandExecutorWithMissingDependency.class)) {
            Class<?> poisonedServiceClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            Class<?> poisonedCommandClass =
                    loader.loadClass(CommandExecutorWithMissingDependency.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("backup-shaped-module");
            container.registerBean(poisonedServiceClass);
            container.registerBean(poisonedCommandClass);
            container.registerBean(SurvivorBean.class);

            container.refresh();

            String[] names = container.getBeanNamesForType(CommandExecutor.class);
            assertThat(names)
                    .as("the transitively-poisoned CommandExecutor bean must not be re-offered "
                            + "by a second, independent enumeration call")
                    .doesNotContain("commandExecutorWithMissingDependency");

            for (String name : names) {
                assertThatCode(() -> container.getBean(name, CommandExecutor.class))
                        .as("mirrors validateCommandExecutorContracts's own per-name loop, "
                                + "including the two-arg getBean(name, type) overload it calls")
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("Diagnostics dedup: the whole register/refresh/enumerate/resolve sequence emits "
            + "exactly ONE SEVERE summary naming each distinct poisoned class exactly once, not "
            + "the two-or-three-times duplicate-listing artifact 07-22-SUMMARY.md observed on "
            + "the real server")
    void diagnosticsNameEachDistinctPoisonedClassExactlyOnce() throws Exception {
        try (BlockingClassLoader loader = newLoaderHiding(MissingDependencyType.class,
                HasMethodReferencingMissingType.class,
                CommandExecutorWithMissingDependency.class)) {
            Class<?> poisonedServiceClass =
                    loader.loadClass(HasMethodReferencingMissingType.class.getName());
            Class<?> poisonedCommandClass =
                    loader.loadClass(CommandExecutorWithMissingDependency.class.getName());

            SimpleContainer container = new SimpleContainer();
            container.setAopProxyResolver(new AopProxyResolver());
            container.setDisplayName("backup-shaped-module");
            container.registerBean(poisonedServiceClass);
            container.registerBean(poisonedCommandClass);
            container.registerBean(SurvivorBean.class);

            container.refresh();
            String[] names = container.getBeanNamesForType(CommandExecutor.class);
            for (String name : names) {
                container.getBean(name, CommandExecutor.class);
            }
        }

        List<LogRecord> severe = new ArrayList<>();
        for (LogRecord record : diagnosticsCaptured) {
            if (Level.SEVERE.equals(record.getLevel())) {
                severe.add(record);
            }
        }
        assertThat(severe).hasSize(1);
        String message = severe.get(0).getMessage();
        int serviceOccurrences = message.split(java.util.regex.Pattern.quote(
                HasMethodReferencingMissingType.class.getName()), -1).length - 1;
        int commandOccurrences = message.split(java.util.regex.Pattern.quote(
                CommandExecutorWithMissingDependency.class.getName()), -1).length - 1;
        assertThat(serviceOccurrences)
                .as("HasMethodReferencingMissingType must be named exactly once")
                .isEqualTo(1);
        assertThat(commandOccurrences)
                .as("CommandExecutorWithMissingDependency must be named exactly once")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Hazard guard (negative control): a genuinely unrelated required-dependency "
            + "failure -- an @Autowired(required=true) field whose type was never registered as "
            + "any bean at all -- still aborts refresh() exactly as before; this plan's "
            + "memoization is scoped exclusively to LinkageError/TypeNotPresentException "
            + "classpath failures")
    void unrelatedRequiredDependencyFailureStillAbortsRefresh() {
        SimpleContainer container = new SimpleContainer();
        container.setAopProxyResolver(new AopProxyResolver());
        container.setDisplayName("negative-control-module");
        container.registerBean(HasUnregisteredRequiredDependency.class);

        assertThatThrownBy(container::refresh)
                .as("a genuinely unrelated, never-registered required dependency must still "
                        + "abort the module -- this plan's memoization must not convert it into "
                        + "a silent skip")
                .isInstanceOf(RuntimeException.class);
    }
}

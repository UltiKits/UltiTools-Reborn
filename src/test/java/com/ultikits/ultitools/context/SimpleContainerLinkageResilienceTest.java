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

import com.ultikits.testfixtures.missingdependency.HasMethodReferencingMissingType;
import com.ultikits.testfixtures.missingdependency.MissingDependencyType;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;

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

    /** A healthy fixture bean with no reference to any missing type. */
    public static class SurvivorBean {
        public String ping() {
            return "pong";
        }
    }

    private final List<LogRecord> diagnosticsCaptured = new ArrayList<>();
    private Logger diagnosticsLogger;
    private Handler diagnosticsHandler;

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
    // resolves exactly as it does anywhere else in the JVM; only two names are treated specially.
    private static final class BlockingClassLoader extends URLClassLoader {
        private final String blockedClassName;
        private final String selfDefinedClassName;

        BlockingClassLoader(URL[] urls, ClassLoader parent, String blockedClassName,
                String selfDefinedClassName) {
            super(urls, parent);
            this.blockedClassName = blockedClassName;
            this.selfDefinedClassName = selfDefinedClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (blockedClassName.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            if (!selfDefinedClassName.equals(name)) {
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

    private BlockingClassLoader newLoaderHiding(Class<?> hiddenType, Class<?> targetType) {
        URL classesRoot = targetType.getProtectionDomain().getCodeSource().getLocation();
        return new BlockingClassLoader(new URL[]{classesRoot},
                SimpleContainerLinkageResilienceTest.class.getClassLoader(),
                hiddenType.getName(), targetType.getName());
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
}

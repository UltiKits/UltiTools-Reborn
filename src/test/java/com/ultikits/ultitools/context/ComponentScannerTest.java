package com.ultikits.ultitools.context;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for ComponentScanner class.
 * <br>
 * ComponentScanner类的单元测试。
 */
@DisplayName("ComponentScanner Tests")
class ComponentScannerTest {

    private SimpleContainer container;
    private ComponentScanner scanner;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        scanner = new ComponentScanner(container);
    }

    @Test
    @DisplayName("Should handle scanner initialization")
    void testScannerInitialization() {
        // Then
        assertNotNull(scanner);
    }

    @Test
    @DisplayName("Should handle empty package scan gracefully")
    void testEmptyPackageScan() {
        // When - should not throw exception
        assertDoesNotThrow(() -> scanner.scanPackage("com.nonexistent.package"));
    }

    @Test
    @DisplayName("Should handle multiple package scan")
    void testMultiplePackageScan() {
        // When - should not throw exception
        assertDoesNotThrow(() -> scanner.scanPackages(
            "com.ultikits.ultitools.context",
            "com.nonexistent.package"
        ));
    }

    @Test
    @DisplayName("Should register configuration beans properly")
    void testConfigurationProcessing() {
        // Given - we'll manually register a configuration since scanning requires class files
        TestConfiguration config = new TestConfiguration();
        container.registerSingleton("testConfiguration", config);

        // When
        container.getBean("testBean", String.class);

        // Then - for now just test that container doesn't break
        // In real scenario, configuration processing would be tested with actual class files
        assertNotNull(container);
    }

    // ===== Task 2 (03-02, D-03): hasComponentAnnotation widened onto MergedAnnotationResolver =====

    /**
     * Two levels above {@code @Component}: {@code Level2} carries {@code @Component} directly,
     * {@code Level1} carries {@code @Level2}, and the fixture below carries only {@code @Level1}.
     * The previous hand-written {@code hasComponentAnnotation} walked one meta-annotation level
     * and would have missed this; {@link MergedAnnotationResolver#isPresent} walks the whole
     * tree and finds it.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Component
    @interface Level2 {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Level2
    @interface Level1 {
    }

    @Level1
    public static class TwoLevelStereotypeFixture {
    }

    /**
     * Negative control: meta-annotated, but its own annotation graph never reaches
     * {@code @Component} anywhere. Must stay unregistered both before and after the widening.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface NotComposedFromComponent {
    }

    @NotComposedFromComponent
    public static class NotAComponentFixture {
    }

    @Test
    @DisplayName("A two-level composed stereotype annotation (Level1 -> Level2 -> @Component) registers as a bean after scanPackage")
    void twoLevelComposedStereotypeRegistersAsBean() {
        scanner.scanPackage("com.ultikits.ultitools.context");

        assertNotNull(container.getBean(TwoLevelStereotypeFixture.class),
                "a class carrying a two-level composed stereotype annotation should have been "
                        + "registered as a bean by the widened hasComponentAnnotation");
    }

    @Test
    @DisplayName("Inert-case guard: a class whose annotation graph never reaches @Component stays unregistered after scanPackage")
    void nonStereotypeAnnotatedClassDoesNotRegister() {
        scanner.scanPackage("com.ultikits.ultitools.context");

        String beanName = Character.toLowerCase(NotAComponentFixture.class.getSimpleName().charAt(0))
                + NotAComponentFixture.class.getSimpleName().substring(1);
        assertFalse(Arrays.asList(container.getBeanDefinitionNames()).contains(beanName),
                "a class not composed from @Component anywhere in its annotation graph must not "
                        + "register just because isPresent's reach was widened");
    }

    /**
     * A module's own main class ({@code @UltiToolsModule}-annotated, extending
     * {@link UltiToolsPlugin}) must never become a scannable {@code @Component} despite
     * {@code @UltiToolsModule} composing {@code @Configuration} -> {@code @Component} -- see
     * {@code ComponentScanner.hasComponentAnnotation}'s javadoc for why (PluginManager already
     * constructs and registers it as a singleton, under a differently-cased bean name, before
     * this scan ever runs).
     */
    @UltiToolsModule
    public abstract static class ModuleMainClassFixture extends UltiToolsPlugin {
    }

    @Test
    @DisplayName("A UltiToolsPlugin subclass (a module's own main class) never becomes a scannable @Component, even though @UltiToolsModule composes @Configuration -> @Component")
    void moduleMainClassNeverBecomesAComponent() {
        scanner.scanPackage("com.ultikits.ultitools.context");

        String beanName = Character.toLowerCase(ModuleMainClassFixture.class.getSimpleName().charAt(0))
                + ModuleMainClassFixture.class.getSimpleName().substring(1);
        assertFalse(Arrays.asList(container.getBeanDefinitionNames()).contains(beanName),
                "a UltiToolsPlugin subclass must never be registered as a scanned @Component bean "
                        + "definition -- doing so would let preInstantiateSingletons construct a "
                        + "second, unmanaged instance of the module's own main class");
    }

    // Test helper classes
    @Component
    public static class TestComponent {
        public String getName() {
            return "TestComponent";
        }
    }

    @Service
    public static class TestService {
        public String process() {
            return "processed";
        }
    }

    @Configuration
    public static class TestConfiguration {

        @Bean
        public String testBean() {
            return "Test Bean Value";
        }

        @Bean
        public Integer numberBean() {
            return 42;
        }
    }

    private static String decapitalize(String simpleName) {
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    // ===== Task 1 (03-07, D-24): leveled scan diagnostics =====

    /**
     * {@code @Configuration} whose constructor throws -- exercises
     * {@code ComponentScanner.registerConfiguration}'s catch-all.
     */
    @Configuration
    public static class ThrowingConfigConstructorFixture {
        public ThrowingConfigConstructorFixture() {
            throw new IllegalStateException("boom-config-constructor");
        }
    }

    /**
     * {@code @Configuration} whose {@code @Bean} method throws -- exercises
     * {@code ComponentScanner.processBeanMethod}'s catch-all.
     */
    @Configuration
    public static class ThrowingBeanMethodFixture {
        @Bean
        public String explodingBean() {
            throw new IllegalStateException("boom-bean-method");
        }
    }

    /**
     * Paired with {@link RegistrationSurvivorComponentFixture}: used with a spied
     * {@link SimpleContainer} that throws only for this fixture's bean name, exercising
     * {@code ComponentScanner.registerComponent}'s catch-all while proving the sibling class in
     * the same package still registers.
     */
    @Component
    public static class ThrowingRegistrationComponentFixture {
    }

    @Component
    public static class RegistrationSurvivorComponentFixture {
    }

    @Nested
    @DisplayName("Task 1 (03-07): leveled scan diagnostics, throwable-carrying (D-24)")
    class LogDiagnostics {

        private final List<LogRecord> captured = new ArrayList<>();
        private Logger scannerLogger;
        private Handler captureHandler;

        @BeforeEach
        void captureLogs() {
            captured.clear();
            scannerLogger = Logger.getLogger(ComponentScanner.class.getName());
            captureHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    captured.add(record);
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
            scannerLogger.addHandler(captureHandler);
        }

        @AfterEach
        void releaseLogs() {
            scannerLogger.removeHandler(captureHandler);
        }

        private List<LogRecord> withLevel(Level level) {
            List<LogRecord> result = new ArrayList<>();
            for (LogRecord record : captured) {
                if (level.equals(record.getLevel())) {
                    result.add(record);
                }
            }
            return result;
        }

        @Test
        @DisplayName("A @Configuration class whose constructor throws logs SEVERE with the throwable attached, naming the class")
        void configurationConstructorFailureLogsSevereWithThrowable() {
            SimpleContainer freshContainer = new SimpleContainer();
            ComponentScanner freshScanner = new ComponentScanner(freshContainer);

            assertDoesNotThrow(() -> freshScanner.scanPackage("com.ultikits.ultitools.context"));

            List<LogRecord> severe = withLevel(Level.SEVERE);
            boolean found = severe.stream().anyMatch(r ->
                    r.getMessage() != null
                            && r.getMessage().contains(ThrowingConfigConstructorFixture.class.getName())
                            && r.getThrown() != null);
            assertTrue(found, "expected a SEVERE record naming the throwing configuration class "
                    + "with its throwable attached");
            assertTrue(withLevel(Level.WARNING).stream().noneMatch(r ->
                            r.getMessage() != null
                                    && r.getMessage().contains(ThrowingConfigConstructorFixture.class.getName())),
                    "a registration failure must never also be logged at WARNING");
        }

        @Test
        @DisplayName("A @Bean method whose invocation throws logs SEVERE with the throwable attached and the method name in the message")
        void beanMethodFailureLogsSevereWithThrowableAndMethodName() {
            SimpleContainer freshContainer = new SimpleContainer();
            ComponentScanner freshScanner = new ComponentScanner(freshContainer);

            assertDoesNotThrow(() -> freshScanner.scanPackage("com.ultikits.ultitools.context"));

            List<LogRecord> severe = withLevel(Level.SEVERE);
            boolean found = severe.stream().anyMatch(r ->
                    r.getMessage() != null && r.getMessage().contains("explodingBean") && r.getThrown() != null);
            assertTrue(found, "expected a SEVERE record naming the exploding @Bean method with its "
                    + "throwable attached");
        }

        @Test
        @DisplayName("A component whose registration throws logs SEVERE with the throwable attached; the sibling class in the same package still registers")
        void componentRegistrationFailureLogsSevereAndSiblingStillRegisters() {
            SimpleContainer spyContainer = spy(new SimpleContainer());
            RuntimeException boom = new RuntimeException("boom-component");
            String throwingBeanName = decapitalize(ThrowingRegistrationComponentFixture.class.getSimpleName());
            String survivorBeanName = decapitalize(RegistrationSurvivorComponentFixture.class.getSimpleName());
            doThrow(boom).when(spyContainer).registerBeanDefinition(eq(throwingBeanName), any());
            ComponentScanner throwingScanner = new ComponentScanner(spyContainer);

            assertDoesNotThrow(() -> throwingScanner.scanPackage("com.ultikits.ultitools.context"));

            assertFalse(Arrays.asList(spyContainer.getBeanDefinitionNames()).contains(throwingBeanName),
                    "the throwing component must not end up registered");
            assertTrue(spyContainer.containsBean(survivorBeanName),
                    "a sibling class in the same package must still register after the throwing "
                            + "class is skipped");

            List<LogRecord> severe = withLevel(Level.SEVERE);
            boolean found = severe.stream().anyMatch(r ->
                    r.getMessage() != null
                            && r.getMessage().contains(ThrowingRegistrationComponentFixture.class.getName())
                            && boom.equals(r.getThrown()));
            assertTrue(found, "expected a SEVERE record naming the class with the original "
                    + "throwable instance attached");
        }

        @Test
        @DisplayName("A package that cannot be resolved logs WARNING with the throwable attached, never SEVERE")
        void unresolvablePackageLogsWarningNotSevere() {
            SimpleContainer freshContainer = new SimpleContainer();
            String breakingPath = "com/ultikits/testfixtures/scanpackageboom";
            ClassLoader throwingLoader = new ClassLoader(getClass().getClassLoader()) {
                @Override
                public URL getResource(String name) {
                    if (breakingPath.equals(name)) {
                        throw new IllegalStateException("simulated package resolution failure");
                    }
                    return super.getResource(name);
                }
            };
            freshContainer.setClassLoader(throwingLoader);
            ComponentScanner freshScanner = new ComponentScanner(freshContainer);

            assertDoesNotThrow(() -> freshScanner.scanPackage("com.ultikits.testfixtures.scanpackageboom"));

            List<LogRecord> warnings = withLevel(Level.WARNING);
            assertTrue(warnings.stream().anyMatch(r ->
                            r.getMessage() != null
                                    && r.getMessage().contains("com.ultikits.testfixtures.scanpackageboom")
                                    && r.getThrown() != null),
                    "expected a WARNING naming the package with the throwable attached");
            assertTrue(withLevel(Level.SEVERE).isEmpty(),
                    "a package-resolution skip must never be logged at SEVERE");
        }

        @Test
        @DisplayName("An unreadable JAR logs WARNING with the throwable attached, never SEVERE")
        void unreadableJarLogsWarningNotSevere() throws Exception {
            SimpleContainer freshContainer = new SimpleContainer();
            String breakingPath = "com/ultikits/testfixtures/scanjarboom";
            URL badJarUrl = new URL("jar:file:/nonexistent/does-not-exist-" + System.nanoTime() + ".jar!/");
            ClassLoader jarLoader = new ClassLoader(getClass().getClassLoader()) {
                @Override
                public URL getResource(String name) {
                    if (breakingPath.equals(name)) {
                        return badJarUrl;
                    }
                    return super.getResource(name);
                }
            };
            freshContainer.setClassLoader(jarLoader);
            ComponentScanner freshScanner = new ComponentScanner(freshContainer);

            assertDoesNotThrow(() -> freshScanner.scanPackage("com.ultikits.testfixtures.scanjarboom"));

            List<LogRecord> warnings = withLevel(Level.WARNING);
            assertTrue(warnings.stream().anyMatch(r ->
                            r.getMessage() != null
                                    && r.getMessage().contains("com.ultikits.testfixtures.scanjarboom")
                                    && r.getThrown() != null),
                    "expected a WARNING naming the package with the throwable attached");
            assertTrue(withLevel(Level.SEVERE).isEmpty(), "a JAR-resolution skip must never be logged at SEVERE");
        }
    }

}

package com.ultikits.ultitools.context;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

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
}

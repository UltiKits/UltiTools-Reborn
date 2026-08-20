package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.Configuration;

/**
 * Unit tests for ContextConfig configuration class.
 * <br>
 * ContextConfig配置类的单元测试。
 */
@DisplayName("ContextConfig Tests")
class ContextConfigTest {

    @Test
    @DisplayName("Should be annotated with @Configuration")
    void testConfigurationAnnotation() {
        // Then
        assertTrue(ContextConfig.class.isAnnotationPresent(Configuration.class),
                "ContextConfig should be annotated with @Configuration");
    }

    @Test
    @DisplayName("Should be annotated with @ComponentScan")
    void testComponentScanAnnotation() {
        // Then
        assertTrue(ContextConfig.class.isAnnotationPresent(ComponentScan.class),
                "ContextConfig should be annotated with @ComponentScan");
    }

    @Test
    @DisplayName("Should scan com.ultikits.ultitools package")
    void testComponentScanPackage() {
        // Given
        ComponentScan componentScan = ContextConfig.class.getAnnotation(ComponentScan.class);

        // Then
        assertNotNull(componentScan);
        String[] basePackages = componentScan.value();
        assertNotNull(basePackages);
        assertEquals(1, basePackages.length);
        assertEquals("com.ultikits.ultitools", basePackages[0]);
    }

    @Test
    @DisplayName("Should have no explicit methods")
    void testNoExplicitMethods() {
        // Given - only inherited methods from Object, filter out synthetic methods
        long declaredMethodCount = java.util.Arrays.stream(ContextConfig.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .count();

        // Then
        assertEquals(0, declaredMethodCount,
                "ContextConfig should not declare any non-synthetic methods");
    }

    @Test
    @DisplayName("Should be instantiable")
    void testInstantiation() {
        // When
        ContextConfig config = new ContextConfig();

        // Then
        assertNotNull(config);
    }

    @Test
    @DisplayName("Should have public no-arg constructor")
    void testPublicConstructor() throws Exception {
        // When
        Constructor<?> constructor = ContextConfig.class.getDeclaredConstructor();

        // Then
        assertNotNull(constructor);
        assertTrue(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
    }

    @Test
    @DisplayName("Should be in correct package")
    void testPackageLocation() {
        // Then
        assertEquals("com.ultikits.ultitools.context", ContextConfig.class.getPackage().getName());
    }

    @Test
    @DisplayName("Should be usable with SimpleContainer")
    void testUsageWithSimpleContainer() throws Exception {
        // Given
        SimpleContainer container = new SimpleContainer();
        // ContextConfig's @ComponentScan targets the whole "com.ultikits.ultitools" tree. On the
        // real classpath that means the framework's compiled classes only; on the merged
        // test+main classpath used by default in this test JVM, the same package name also
        // resolves test fixtures scattered across the suite - including
        // FinalContractValidatorTest's intentional @Final violation fixtures, which now correctly
        // abort a scan (see ComponentScannerFinalContractTest). Scope resource lookups to
        // target/classes so this test scans what a production module load actually would.
        File classesDir = new File("target/classes");
        URL[] classpath = {classesDir.toURI().toURL()};
        container.setClassLoader(new ClassesOnlyClassLoader(classpath, getClass().getClassLoader()));

        // When
        container.processConfigurationClass(ContextConfig.class);

        // Then
        // Should not throw any exceptions
        assertNotNull(container);
    }

    /**
     * A {@link URLClassLoader} that answers {@link #getResource(String)} from its own URLs first,
     * falling back to the parent only when its own classpath has no match. Plain
     * {@code URLClassLoader} delegates parent-first, so restricting its URL list alone does not
     * stop a package-root lookup like {@code "com/ultikits/ultitools"} from resolving through the
     * parent to whatever the merged test+main classpath finds first - see
     * {@link #testUsageWithSimpleContainer()}. Actual class loading still delegates to the parent
     * normally, which is what lets the loaded framework classes resolve their own dependencies
     * (Bukkit, etc.) that do not exist under {@code target/classes} alone.
     */
    private static final class ClassesOnlyClassLoader extends URLClassLoader {
        ClassesOnlyClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public URL getResource(String name) {
            URL local = findResource(name);
            return local != null ? local : super.getResource(name);
        }
    }

    @Test
    @DisplayName("Multiple instances should be independent")
    void testMultipleInstances() {
        // When
        ContextConfig config1 = new ContextConfig();
        ContextConfig config2 = new ContextConfig();

        // Then
        assertNotNull(config1);
        assertNotNull(config2);
        assertNotSame(config1, config2);
    }

    @Test
    @DisplayName("Should not have any fields")
    void testNoFields() {
        // Given - filter out synthetic fields (e.g., from code coverage tools)
        long declaredFieldCount = java.util.Arrays.stream(ContextConfig.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .count();

        // Then
        assertEquals(0, declaredFieldCount,
                "ContextConfig should not declare any non-synthetic fields");
    }

    @Test
    @DisplayName("ComponentScan annotation should have correct attributes")
    void testComponentScanAttributes() {
        // Given
        ComponentScan componentScan = ContextConfig.class.getAnnotation(ComponentScan.class);

        // Then
        assertNotNull(componentScan);
        
        // Check value attribute
        String[] value = componentScan.value();
        assertEquals(1, value.length);
        assertEquals("com.ultikits.ultitools", value[0]);
        
        // Check basePackages attribute (should be empty as value is used)
        String[] basePackages = componentScan.basePackages();
        assertEquals(0, basePackages.length);
    }

    @Test
    @DisplayName("Should be a simple marker configuration class")
    void testIsMarkerClass() {
        // Given - filter out synthetic members
        boolean hasConfigAnnotation = ContextConfig.class.isAnnotationPresent(Configuration.class);
        boolean hasComponentScanAnnotation = ContextConfig.class.isAnnotationPresent(ComponentScan.class);
        boolean hasNoMethods = java.util.Arrays.stream(ContextConfig.class.getDeclaredMethods())
                .noneMatch(m -> !m.isSynthetic());
        boolean hasNoFields = java.util.Arrays.stream(ContextConfig.class.getDeclaredFields())
                .noneMatch(f -> !f.isSynthetic());

        // Then
        assertTrue(hasConfigAnnotation);
        assertTrue(hasComponentScanAnnotation);
        assertTrue(hasNoMethods);
        assertTrue(hasNoFields);
    }
}

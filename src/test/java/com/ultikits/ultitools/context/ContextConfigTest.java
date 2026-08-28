package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;

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
    @DisplayName("Should no longer be annotated with @ComponentScan")
    void testComponentScanAnnotationAbsent() {
        // @ComponentScan("com.ultikits.ultitools") was removed in 6.3.0 (D-23): nothing in
        // src/main ever invoked SimpleContainer#processConfigurationClass against it, so the
        // declaration was a scan nobody performed. See ContextConfig's own javadoc.
        assertFalse(ContextConfig.class.isAnnotationPresent(ComponentScan.class),
                "ContextConfig should no longer be annotated with @ComponentScan");
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
    void testUsageWithSimpleContainer() {
        // Given
        SimpleContainer container = new SimpleContainer();

        // When
        container.processConfigurationClass(ContextConfig.class);

        // Then
        // Should not throw any exceptions
        assertNotNull(container);
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
    @DisplayName("getAnnotation(ComponentScan.class) should return null now that the declaration "
            + "is gone")
    void testComponentScanAnnotationInstanceIsNull() {
        // Given
        ComponentScan componentScan = ContextConfig.class.getAnnotation(ComponentScan.class);

        // Then
        assertNull(componentScan,
                "ContextConfig no longer carries @ComponentScan, so its instance must be null");
    }

    @Test
    @DisplayName("Should be a simple marker configuration class with no scan declaration")
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
        assertFalse(hasComponentScanAnnotation,
                "ContextConfig should no longer be annotated with @ComponentScan (D-23)");
        assertTrue(hasNoMethods);
        assertTrue(hasNoFields);
    }
}

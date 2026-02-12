package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Service;

/**
 * Tests for @ConditionalOnConfig integration with ComponentScanner.
 */
@DisplayName("@ConditionalOnConfig Tests")
class ConditionalOnConfigTest {

    @TempDir
    File tempDir;

    private SimpleContainer container;
    private UltiToolsPlugin mockPlugin;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());

        // Register the mock plugin so ComponentScanner can find it
        container.registerType(UltiToolsPlugin.class, mockPlugin);
    }

    @AfterEach
    void tearDown() {
        container.close();
    }

    // === Test Beans ===

    @Service
    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeatureA")
    public static class ConditionalServiceA {
    }

    @Service
    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeatureB", negate = true)
    public static class NegatedConditionalService {
    }

    @Service
    public static class UnconditionalService {
    }

    @Service
    @ConditionalOnConfig(value = "nonexistent.yml", path = "anything")
    public static class MissingConfigService {
    }

    @Service
    @ConditionalOnConfig(value = "config/config.yml", path = "nested.deep.key")
    public static class NestedKeyService {
    }

    // === Helper ===

    private void writeYaml(String relativePath, String content) throws IOException {
        File configFile = new File(tempDir, relativePath);
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    /**
     * Use ComponentScanner's shouldRegister logic via processClass.
     * Since processClass is private, we test through scanPackages behavior.
     * But scanPackages needs actual classes on classpath — so we test the
     * shouldRegister logic directly by reflectively invoking it.
     */
    private boolean invokesShouldRegister(Class<?> clazz) throws Exception {
        ComponentScanner scanner = new ComponentScanner(container);
        java.lang.reflect.Method shouldRegister =
                ComponentScanner.class.getDeclaredMethod("shouldRegister", Class.class);
        shouldRegister.setAccessible(true);
        return (boolean) shouldRegister.invoke(scanner, clazz);
    }

    // === Annotation Tests ===

    @Nested
    @DisplayName("Annotation Structure")
    class AnnotationStructure {

        @Test
        @DisplayName("Should have correct annotation values")
        void annotationValues() {
            ConditionalOnConfig cond = ConditionalServiceA.class.getAnnotation(ConditionalOnConfig.class);
            assertNotNull(cond);
            assertEquals("config/config.yml", cond.value());
            assertEquals("enableFeatureA", cond.path());
            assertFalse(cond.negate());
        }

        @Test
        @DisplayName("Should support negate parameter")
        void negateParam() {
            ConditionalOnConfig cond = NegatedConditionalService.class.getAnnotation(ConditionalOnConfig.class);
            assertNotNull(cond);
            assertTrue(cond.negate());
        }
    }

    // === shouldRegister Tests ===

    @Nested
    @DisplayName("Condition Evaluation")
    class ConditionEvaluation {

        @Test
        @DisplayName("Class without annotation should always register")
        void noAnnotationAlwaysRegisters() throws Exception {
            assertTrue(invokesShouldRegister(UnconditionalService.class));
        }

        @Test
        @DisplayName("Config value true should register")
        void configTrueRegisters() throws Exception {
            writeYaml("config/config.yml", "enableFeatureA: true\n");
            assertTrue(invokesShouldRegister(ConditionalServiceA.class));
        }

        @Test
        @DisplayName("Config value false should not register")
        void configFalseSkips() throws Exception {
            writeYaml("config/config.yml", "enableFeatureA: false\n");
            assertFalse(invokesShouldRegister(ConditionalServiceA.class));
        }

        @Test
        @DisplayName("Missing config key should not register (defaults to false)")
        void missingKeySkips() throws Exception {
            writeYaml("config/config.yml", "otherKey: true\n");
            assertFalse(invokesShouldRegister(ConditionalServiceA.class));
        }

        @Test
        @DisplayName("Missing config file should not register")
        void missingFileSkips() throws Exception {
            assertFalse(invokesShouldRegister(MissingConfigService.class));
        }

        @Test
        @DisplayName("Negate=true with config false should register")
        void negateWithFalseRegisters() throws Exception {
            writeYaml("config/config.yml", "enableFeatureB: false\n");
            assertTrue(invokesShouldRegister(NegatedConditionalService.class));
        }

        @Test
        @DisplayName("Negate=true with config true should not register")
        void negateWithTrueSkips() throws Exception {
            writeYaml("config/config.yml", "enableFeatureB: true\n");
            assertFalse(invokesShouldRegister(NegatedConditionalService.class));
        }

        @Test
        @DisplayName("Negate=true with missing file should register")
        void negateWithMissingFileRegisters() throws Exception {
            // negate=true means register when config is false; missing file = false
            assertTrue(invokesShouldRegister(NegatedConditionalService.class));
        }

        @Test
        @DisplayName("Nested YAML key should be evaluated correctly")
        void nestedKeyEvaluation() throws Exception {
            writeYaml("config/config.yml", "nested:\n  deep:\n    key: true\n");
            assertTrue(invokesShouldRegister(NestedKeyService.class));
        }

        @Test
        @DisplayName("Nested YAML key false should not register")
        void nestedKeyFalse() throws Exception {
            writeYaml("config/config.yml", "nested:\n  deep:\n    key: false\n");
            assertFalse(invokesShouldRegister(NestedKeyService.class));
        }
    }

    // === Edge Cases ===

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("No plugin in container should register by default")
        void noPluginRegisters() throws Exception {
            SimpleContainer emptyContainer = new SimpleContainer();
            ComponentScanner scanner = new ComponentScanner(emptyContainer);
            java.lang.reflect.Method shouldRegister =
                    ComponentScanner.class.getDeclaredMethod("shouldRegister", Class.class);
            shouldRegister.setAccessible(true);
            assertTrue((boolean) shouldRegister.invoke(scanner, ConditionalServiceA.class));
            emptyContainer.close();
        }

        @Test
        @DisplayName("Null resourceFolderPath should register by default")
        void nullFolderRegisters() throws Exception {
            when(mockPlugin.getResourceFolderPath()).thenReturn(null);
            assertTrue(invokesShouldRegister(ConditionalServiceA.class));
        }
    }
}

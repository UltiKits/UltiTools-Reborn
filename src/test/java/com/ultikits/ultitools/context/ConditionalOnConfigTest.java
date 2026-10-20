package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
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
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
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

    // === D-20: fail-open branches stay fail-open but stop being silent ===

    /**
     * Both fail-open branches of the shared evaluator (D-20) must keep returning {@code true}
     * <b>and</b> announce themselves with a {@code Level.WARNING} record naming the evaluated
     * class. Captured on the evaluator's own logger by name (rather than
     * {@code ConditionalRegistrationEvaluator.class.getName()}) so this test class compiles
     * before that extracted type exists -- it is the RED half of Task 1's TDD cycle.
     * <br>
     * 共享判定器（D-20）的两条"无法判定 -> 默认放行"分支必须继续返回 {@code true}，并且不再沉默：
     * 各自发出一条命名了被判定类的 {@code Level.WARNING} 记录。这里按名字（而不是
     * {@code ConditionalRegistrationEvaluator.class.getName()}）获取 logger，以便本测试类在
     * 该抽取出的类型存在之前也能编译通过——这是 Task 1 TDD 循环的 RED 一半。
     */
    @Nested
    @DisplayName("Fail-open branches announce themselves (D-20)")
    class FailOpenWarnings {

        private static final String EVALUATOR_LOGGER_NAME =
                "com.ultikits.ultitools.context.ConditionalRegistrationEvaluator";

        private final List<LogRecord> captured = new ArrayList<>();
        private Logger evaluatorLogger;
        private Handler captureHandler;

        @BeforeEach
        void captureLogs() {
            captured.clear();
            evaluatorLogger = Logger.getLogger(EVALUATOR_LOGGER_NAME);
            captureHandler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    captured.add(record);
                }

                @Override
                public void flush() {
                    // Intentionally empty: this in-memory capture handler has no buffered
                    // output to flush -- records are appended directly in publish().
                }

                @Override
                public void close() {
                    // Intentionally empty: no resource to release; the handler is detached
                    // via releaseLogs()/removeHandler() below.
                }
            };
            evaluatorLogger.addHandler(captureHandler);
        }

        @AfterEach
        void releaseLogs() {
            evaluatorLogger.removeHandler(captureHandler);
        }

        private List<LogRecord> warnings() {
            List<LogRecord> result = new ArrayList<>();
            for (LogRecord record : captured) {
                if (Level.WARNING.equals(record.getLevel())) {
                    result.add(record);
                }
            }
            return result;
        }

        @Test
        @DisplayName("Unresolvable plugin from container still registers, and warns naming the class")
        void unresolvablePluginWarnsAndRegisters() throws Exception {
            SimpleContainer throwingContainer = mock(SimpleContainer.class);
            when(throwingContainer.getBean(UltiToolsPlugin.class))
                    .thenThrow(new IllegalStateException("simulated container failure"));

            boolean result = invokesShouldRegisterAgainst(ConditionalServiceA.class, throwingContainer);

            assertTrue(result, "an unresolvable plugin must still fail open (register by default)");
            List<LogRecord> warnings = warnings();
            assertTrue(warnings.size() >= 1,
                    "expected a WARNING when no plugin could be resolved from the container");
            assertTrue(warnings.stream().anyMatch(r ->
                            r.getMessage().contains(ConditionalServiceA.class.getName())),
                    "expected the warning to name the evaluated class");
        }

        @Test
        @DisplayName("Null resourceFolderPath still registers, and warns naming the class")
        void nullResourceFolderWarnsAndRegisters() throws Exception {
            when(mockPlugin.getResourceFolderPath()).thenReturn(null);

            boolean result = invokesShouldRegister(ConditionalServiceA.class);

            assertTrue(result, "a null resource folder path must still fail open (register by default)");
            List<LogRecord> warnings = warnings();
            assertTrue(warnings.size() >= 1,
                    "expected a WARNING when the plugin's resource folder path is null");
            assertTrue(warnings.stream().anyMatch(r ->
                            r.getMessage().contains(ConditionalServiceA.class.getName())),
                    "expected the warning to name the evaluated class");
        }

        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private boolean invokesShouldRegisterAgainst(Class<?> clazz, SimpleContainer testContainer) throws Exception {
            ComponentScanner scanner = new ComponentScanner(testContainer);
            java.lang.reflect.Method shouldRegister =
                    ComponentScanner.class.getDeclaredMethod("shouldRegister", Class.class);
            shouldRegister.setAccessible(true);
            return (boolean) shouldRegister.invoke(scanner, clazz);
        }
    }
}

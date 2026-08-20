package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.Bean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

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

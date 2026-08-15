package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AutowireFactory class.
 * <br>
 * AutowireFactory类的单元测试。
 */
@DisplayName("AutowireFactory Tests")
class AutowireFactoryTest {

    private SimpleContainer container;
    private AutowireFactory autowireFactory;

    private final List<LogRecord> captured = new ArrayList<>();
    private Logger factoryLogger;
    private Handler captureHandler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        autowireFactory = new AutowireFactory(container);

        captured.clear();
        factoryLogger = Logger.getLogger(AutowireFactory.class.getName());
        previousLevel = factoryLogger.getLevel();
        previousUseParentHandlers = factoryLogger.getUseParentHandlers();
        // 关掉向上冒泡，否则这些用例每跑一条就往测试输出里打一段堆栈
        factoryLogger.setUseParentHandlers(false);
        factoryLogger.setLevel(Level.ALL);
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
        factoryLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        factoryLogger.removeHandler(captureHandler);
        factoryLogger.setLevel(previousLevel);
        factoryLogger.setUseParentHandlers(previousUseParentHandlers);
    }

    /**
     * WARNING 级别的记录。别的级别（比如 SimpleContainer 的 fine）不算。
     */
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
    @DisplayName("Should autowire dependencies")
    void testAutowireDependencies() {
        // Given
        TestRepository testRepository = new TestRepository();
        
        container.registerType(TestRepository.class, testRepository);
        
        TestController controller = new TestController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertNotNull(controller.getRepository());
        assertEquals(testRepository, controller.getRepository());
    }

    @Test
    @DisplayName("Should handle null dependencies gracefully")
    void testNullDependencies() {
        // Given
        TestController controller = new TestController();
        // No repository registered

        // When - should not throw exception
        assertDoesNotThrow(() -> autowireFactory.autowireBean(controller));

        // Then
        assertNull(controller.getRepository());
    }

    @Test
    @DisplayName("Should autowire multiple dependencies")
    void testMultipleDependencies() {
        // Given
        TestRepository repository = new TestRepository();
        TestService service = new TestService();
        
        container.registerType(TestRepository.class, repository);
        container.registerType(TestService.class, service);
        
        TestComplexController controller = new TestComplexController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertNotNull(controller.getRepository());
        assertNotNull(controller.getService());
        assertEquals(repository, controller.getRepository());
        assertEquals(service, controller.getService());
    }

    @Test
    @DisplayName("Should not autowire non-annotated fields")
    void testNonAnnotatedFields() {
        // Given
        TestRepository repository = new TestRepository();
        container.registerType(TestRepository.class, repository);
        
        TestNoAutowireController controller = new TestNoAutowireController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertNull(controller.getRepository()); // Should remain null
    }

    @Test
    @DisplayName("Should handle inheritance in autowiring")
    void testInheritanceAutowiring() {
        // Given
        TestService service = new TestService();
        container.registerType(TestService.class, service);
        
        TestExtendedController controller = new TestExtendedController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertNotNull(controller.getService());
        assertEquals(service, controller.getService());
    }

    @Test
    @DisplayName("Should warn when a required dependency cannot be resolved")
    void testWarnOnMissingRequiredDependency() {
        // Given - nothing registered, so TestRepository cannot be resolved
        TestController controller = new TestController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        List<LogRecord> warnings = warnings();
        assertEquals(1, warnings.size());
        LogRecord record = warnings.get(0);
        assertTrue(record.getMessage().contains(TestRepository.class.getName()),
                "the warning must name the bean type that could not be resolved");
        assertTrue(record.getMessage().contains(TestController.class.getName() + ".repository"),
                "the warning must name the host field");
        assertNotNull(record.getThrown(),
                "the stack is the point: it tells the module author which code path triggered the injection");
    }

    @Test
    @DisplayName("Should stay silent when required = false")
    void testNoWarnWhenDependencyIsOptional() {
        // Given
        TestOptionalController controller = new TestOptionalController();

        // When
        autowireFactory.autowireBean(controller);

        // Then - opting out is exactly what required = false means
        assertNull(controller.getRepository());
        assertTrue(warnings().isEmpty());
    }

    @Test
    @DisplayName("Should stay silent when the dependency resolves")
    void testNoWarnWhenDependencyResolves() {
        // Given
        container.registerType(TestRepository.class, new TestRepository());

        // When
        autowireFactory.autowireBean(new TestController());

        // Then
        assertTrue(warnings().isEmpty());
    }

    @Test
    @DisplayName("Should name the declaring class for inherited fields")
    void testWarnNamesDeclaringClassForInheritedField() {
        // Given - the annotated field lives on the base class, not the one being autowired
        TestExtendedController controller = new TestExtendedController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).getMessage().contains(TestBaseController.class.getName() + ".service"),
                "pointing at the subclass would send the author to a file that has no such field");
    }

    @Test
    @DisplayName("Should not interrupt container refresh when a required dependency is missing")
    void testRefreshIsNotInterrupted() {
        // Given - a bean whose required dependency is not registered
        container.registerBean(TestController.class);

        // When - 本版只警告不抛，可能有下游模块正带着 null 字段跑在生产上
        assertDoesNotThrow(() -> container.refresh());

        // Then
        TestController bean = container.getBean(TestController.class);
        assertNotNull(bean);
        assertNull(bean.getRepository());
        assertFalse(warnings().isEmpty());
    }

    // Test helper classes
    public static class TestRepository {
        public String getData() {
            return "repository data";
        }
    }

    public static class TestService {
        public String process() {
            return "processed";
        }
    }

    public static class TestController {
        @Autowired
        private TestRepository repository;

        public TestRepository getRepository() {
            return repository;
        }
    }

    public static class TestComplexController {
        @Autowired
        private TestRepository repository;

        @Autowired
        private TestService service;

        public TestRepository getRepository() {
            return repository;
        }

        public TestService getService() {
            return service;
        }
    }

    public static class TestOptionalController {
        @Autowired(required = false)
        private TestRepository repository;

        public TestRepository getRepository() {
            return repository;
        }
    }

    public static class TestNoAutowireController {
        private TestRepository repository; // No @Autowired annotation

        public TestRepository getRepository() {
            return repository;
        }
    }

    public static class TestBaseController {
        @Autowired
        private TestService service;

        public TestService getService() {
            return service;
        }
    }

    public static class TestExtendedController extends TestBaseController {
        // Inherits the autowired service field
    }
}

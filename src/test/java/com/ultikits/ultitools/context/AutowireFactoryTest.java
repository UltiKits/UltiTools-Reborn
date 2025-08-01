package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

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

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        autowireFactory = new AutowireFactory(container);
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

package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.exceptions.ContainerException;
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
    @DisplayName("Should handle null dependencies gracefully when optional")
    void testNullDependencies() {
        // Given
        TestOptionalController controller = new TestOptionalController();
        // No repository registered, but the field is @Autowired(required = false)

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
    @DisplayName("Should throw when a required dependency cannot be resolved")
    void testThrowsOnMissingRequiredDependency() {
        // Given - nothing registered, so TestRepository cannot be resolved
        TestController controller = new TestController();

        // When
        ContainerException exception = assertThrows(ContainerException.class,
                () -> autowireFactory.autowireBean(controller));

        // Then
        assertTrue(exception.getMessage().contains(TestRepository.class.getName()),
                "the exception must name the bean type that could not be resolved");
        assertTrue(exception.getMessage().contains(TestController.class.getName() + ".repository"),
                "the exception must name the host field");
    }

    @Test
    @DisplayName("Should stay silent when required = false")
    void testNoThrowWhenDependencyIsOptional() {
        // Given
        TestOptionalController controller = new TestOptionalController();

        // When
        autowireFactory.autowireBean(controller);

        // Then - opting out is exactly what required = false means
        assertNull(controller.getRepository());
    }

    @Test
    @DisplayName("Should stay silent when the dependency resolves")
    void testNoThrowWhenDependencyResolves() {
        // Given
        container.registerType(TestRepository.class, new TestRepository());

        // When / Then
        assertDoesNotThrow(() -> autowireFactory.autowireBean(new TestController()));
    }

    @Test
    @DisplayName("Should name the declaring class for inherited fields")
    void testThrowNamesDeclaringClassForInheritedField() {
        // Given - the annotated field lives on the base class, not the one being autowired
        TestExtendedController controller = new TestExtendedController();

        // When
        ContainerException exception = assertThrows(ContainerException.class,
                () -> autowireFactory.autowireBean(controller));

        // Then
        assertTrue(exception.getMessage().contains(TestBaseController.class.getName() + ".service"),
                "pointing at the subclass would send the author to a file that has no such field");
    }

    @Test
    @DisplayName("Container refresh aborts when a required dependency is missing")
    void testRefreshAbortsOnMissingRequiredDependency() {
        // Given - a bean whose required dependency is not registered
        container.registerBean(TestController.class);

        // When / Then - required = true now aborts the container instead of leaving a null field
        assertThrows(RuntimeException.class, () -> container.refresh());
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

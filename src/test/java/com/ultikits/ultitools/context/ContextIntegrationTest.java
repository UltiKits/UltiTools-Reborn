package com.ultikits.ultitools.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the entire context package.
 * <br>
 * 整个context包的集成测试。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Context Integration Tests")
class ContextIntegrationTest {

    private SimpleContainer container;

    @BeforeAll
    void setUp() {
        container = new SimpleContainer();
    }

    @Test
    @DisplayName("Should integrate SimpleContainer with BeanFactory")
    void testSimpleContainerWithBeanFactory() {
        // Given
        BeanFactory factory = container.getBeanFactory();
        String testBean = "Integration Test Bean";

        // When
        factory.registerSingleton("integrationBean", testBean);
        String retrieved = (String) factory.getBean("integrationBean");

        // Then
        assertNotNull(retrieved);
        assertEquals(testBean, retrieved);
        assertTrue(container.containsBean("integrationBean"));
    }

    @Test
    @DisplayName("Should integrate SimpleContainer with AutowireFactory")
    void testSimpleContainerWithAutowireFactory() {
        // Given
        AutowireFactory autowireFactory = container.getAutowireCapableBeanFactory();
        TestIntegrationRepository repository = new TestIntegrationRepository();
        
        container.registerType(TestIntegrationRepository.class, repository);
        TestIntegrationController controller = new TestIntegrationController();

        // When
        autowireFactory.autowireBean(controller);

        // Then
        assertNotNull(controller.getRepository());
        assertEquals(repository, controller.getRepository());
    }

    @Test
    @DisplayName("Should handle full container lifecycle")
    void testFullContainerLifecycle() {
        // Given
        SimpleContainer testContainer = new SimpleContainer();
        
        // When
        testContainer.registerSingleton("lifecycleBean", "lifecycle test");
        testContainer.start();
        
        // Then
        assertTrue(testContainer.isRunning());
        assertNotNull(testContainer.getBean("lifecycleBean"));
        
        // When
        testContainer.stop();
        
        // Then
        assertFalse(testContainer.isRunning());
        
        // When
        testContainer.close();
        
        // Then
        assertFalse(testContainer.containsBean("lifecycleBean"));
    }

    @Test
    @DisplayName("Should support complex dependency injection scenarios")
    void testComplexDependencyInjection() {
        // Given
        TestIntegrationRepository repository = new TestIntegrationRepository();
        TestIntegrationService service = new TestIntegrationService();
        
        container.registerType(TestIntegrationRepository.class, repository);
        container.registerType(TestIntegrationService.class, service);
        container.registerBean(TestIntegrationController.class);

        // When
        TestIntegrationController controller = container.getBean(TestIntegrationController.class);

        // Then
        assertNotNull(controller);
        assertNotNull(controller.getRepository());
        assertEquals(repository, controller.getRepository());
    }

    @Test
    @DisplayName("Should handle bean scopes correctly")
    void testBeanScopes() {
        // Given
        String beanName = "scopeTestBean";
        String instance = "scope test";

        // When
        container.registerSingleton(beanName, instance);

        // Then
        assertTrue(container.isSingleton(beanName));
        assertFalse(container.isPrototype(beanName));
        
        // Verify same instance is returned
        Object first = container.getBean(beanName);
        Object second = container.getBean(beanName);
        assertSame(first, second);
    }

    @Test
    @DisplayName("Should support parent-child container hierarchies")
    void testParentChildHierarchy() {
        // Given
        SimpleContainer parent = new SimpleContainer();
        parent.registerSingleton("parentBean", "parent value");
        
        SimpleContainer child = new SimpleContainer(parent);
        child.registerSingleton("childBean", "child value");

        // When
        Object parentBean = child.getBean("parentBean");
        Object childBean = child.getBean("childBean");

        // Then
        assertNotNull(parentBean);
        assertNotNull(childBean);
        assertEquals("parent value", parentBean);
        assertEquals("child value", childBean);
        
        // Parent should not see child beans
        assertNull(parent.getBean("childBean"));
    }

    // Test helper classes for integration testing
    public static class TestIntegrationRepository {
        public String findData() {
            return "integration data";
        }
    }

    public static class TestIntegrationService {
        public String processData(String data) {
            return "processed: " + data;
        }
    }

    public static class TestIntegrationController {
        @com.ultikits.ultitools.annotations.Autowired
        private TestIntegrationRepository repository;

        public TestIntegrationRepository getRepository() {
            return repository;
        }

        public String handleRequest() {
            if (repository != null) {
                return repository.findData();
            }
            return "no data";
        }
    }
}

package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for SimpleContainer class.
 * <br>
 * SimpleContainer类的单元测试。
 */
@DisplayName("SimpleContainer Tests")
class SimpleContainerTest {

    private SimpleContainer container;
    private AutoCloseable mockitoCloseable;

    @Mock
    private BeanPostProcessor mockBeanPostProcessor;

    @BeforeEach
    void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        container = new SimpleContainer();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close container to clean up beans
        if (container != null) {
            container.close();
        }
        // Close Mockito resources
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    @Test
    @DisplayName("Should register and retrieve singleton bean")
    void testRegisterAndRetrieveSingleton() {
        // Given
        String beanName = "testBean";
        String beanInstance = "Test Instance";

        // When
        container.registerSingleton(beanName, beanInstance);
        Object retrievedBean = container.getBean(beanName);

        // Then
        assertNotNull(retrievedBean);
        assertEquals(beanInstance, retrievedBean);
        assertTrue(container.containsBean(beanName));
    }

    @Test
    @DisplayName("Should return null for non-existent bean")
    void testGetNonExistentBean() {
        // When
        Object result = container.getBean("nonExistent");

        // Then
        assertNull(result);
        assertFalse(container.containsBean("nonExistent"));
    }

    @Test
    @DisplayName("Should register and retrieve bean by type")
    void testRegisterAndRetrieveByType() {
        // Given
        String testInstance = "Test String";

        // When
        container.registerType(String.class, testInstance);
        String retrievedBean = container.getBean(String.class);

        // Then
        assertNotNull(retrievedBean);
        assertEquals(testInstance, retrievedBean);
    }

    @Test
    @DisplayName("Should register bean with constructor")
    void testRegisterBeanWithConstructor() {
        // When
        container.registerBean(TestService.class);
        TestService service = container.getBean(TestService.class);

        // Then
        assertNotNull(service);
        assertEquals("TestService", service.getName());
    }

    @Test
    @DisplayName("Should handle bean post processors")
    void testBeanPostProcessors() {
        // Given
        when(mockBeanPostProcessor.postProcessBeforeInitialization(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mockBeanPostProcessor.postProcessAfterInitialization(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        container.addBeanPostProcessor(mockBeanPostProcessor);

        // When
        container.registerBean(TestService.class);
        TestService service = container.getBean(TestService.class);

        // Then
        assertNotNull(service);
        verify(mockBeanPostProcessor).postProcessBeforeInitialization(any(TestService.class), eq("testService"));
        verify(mockBeanPostProcessor).postProcessAfterInitialization(any(TestService.class), eq("testService"));
    }

    @Test
    @DisplayName("Should check singleton scope correctly")
    void testSingletonScope() {
        // Given
        String beanName = "singletonBean";
        String instance = "singleton";

        // When
        container.registerSingleton(beanName, instance);

        // Then
        assertTrue(container.isSingleton(beanName));
        assertFalse(container.isPrototype(beanName));
    }

    @Test
    @DisplayName("Should get beans by type")
    void testGetBeansByType() {
        // Given
        String instance1 = "String1";
        String instance2 = "String2";
        
        container.registerSingleton("string1", instance1);
        container.registerSingleton("string2", instance2);

        // When
        java.util.Map<String, String> stringBeans = container.getBeansOfType(String.class);

        // Then
        assertEquals(2, stringBeans.size());
        assertTrue(stringBeans.containsValue(instance1));
        assertTrue(stringBeans.containsValue(instance2));
    }

    @Test
    @DisplayName("Should get bean names for type")
    void testGetBeanNamesForType() {
        // Given
        String instance = "test";
        container.registerSingleton("stringBean", instance);

        // When
        String[] beanNames = container.getBeanNamesForType(String.class);

        // Then
        assertEquals(1, beanNames.length);
        assertEquals("stringBean", beanNames[0]);
    }

    @Test
    @DisplayName("Should manage container lifecycle")
    void testContainerLifecycle() {
        // Given
        assertFalse(container.isRunning());

        // When
        container.start();

        // Then
        assertTrue(container.isRunning());

        // When
        container.stop();

        // Then
        assertFalse(container.isRunning());
    }

    @Test
    @DisplayName("Should get bean factory")
    void testGetBeanFactory() {
        // When
        BeanFactory factory = container.getBeanFactory();

        // Then
        assertNotNull(factory);
    }

    @Test
    @DisplayName("Should get autowire capable bean factory")
    void testGetAutowireCapableBeanFactory() {
        // When
        AutowireFactory factory = container.getAutowireCapableBeanFactory();

        // Then
        assertNotNull(factory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bean1", "bean2", "bean3"})
    @DisplayName("Should handle multiple bean registrations")
    void testMultipleBeanRegistrations(String beanName) {
        // Given
        String instance = "Instance for " + beanName;

        // When
        container.registerSingleton(beanName, instance);

        // Then
        assertTrue(container.containsBean(beanName));
        assertEquals(instance, container.getBean(beanName));
    }

    @Test
    @DisplayName("Should close container properly")
    void testCloseContainer() {
        // Given - use a separate container for this test
        SimpleContainer testContainer = new SimpleContainer();
        testContainer.registerSingleton("test", "instance");
        assertTrue(testContainer.containsBean("test"));

        // When
        testContainer.close();

        // Then
        assertFalse(testContainer.containsBean("test"));
    }

    @Test
    @DisplayName("Should handle parent-child container relationship")
    void testParentChildContainer() {
        // Given
        SimpleContainer parent = new SimpleContainer();
        SimpleContainer child = null;
        try {
            parent.registerSingleton("parentBean", "parentValue");
            
            child = new SimpleContainer(parent);

            // When
            Object parentBean = child.getBean("parentBean");

            // Then
            assertNotNull(parentBean);
            assertEquals("parentValue", parentBean);
        } finally {
            // Clean up child first, then parent
            if (child != null) {
                child.close();
            }
            parent.close();
        }
    }

    // Test helper class
    public static class TestService {
        public String getName() {
            return "TestService";
        }
    }
}

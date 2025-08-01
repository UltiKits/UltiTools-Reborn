package com.ultikits.ultitools.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BeanFactory class.
 * <br>
 * BeanFactory类的单元测试。
 */
@DisplayName("BeanFactory Tests")
class BeanFactoryTest {

    private SimpleContainer container;
    private BeanFactory beanFactory;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        beanFactory = new BeanFactory(container);
    }

    @Test
    @DisplayName("Should register singleton through factory")
    void testRegisterSingleton() {
        // Given
        String beanName = "testBean";
        String instance = "Test Instance";

        // When
        beanFactory.registerSingleton(beanName, instance);

        // Then
        Object retrievedBean = beanFactory.getBean(beanName);
        assertNotNull(retrievedBean);
        assertEquals(instance, retrievedBean);
    }

    @Test
    @DisplayName("Should get bean by name through factory")
    void testGetBeanByName() {
        // Given
        String beanName = "namedBean";
        String instance = "Named Instance";
        container.registerSingleton(beanName, instance);

        // When
        Object result = beanFactory.getBean(beanName);

        // Then
        assertNotNull(result);
        assertEquals(instance, result);
    }

    @Test
    @DisplayName("Should get bean by type through factory")
    void testGetBeanByType() {
        // Given
        String instance = "Typed Instance";
        container.registerType(String.class, instance);

        // When
        String result = beanFactory.getBean(String.class);

        // Then
        assertNotNull(result);
        assertEquals(instance, result);
    }

    @Test
    @DisplayName("Should return null for non-existent bean")
    void testGetNonExistentBean() {
        // When
        Object result = beanFactory.getBean("nonExistent");

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for non-existent type")
    void testGetNonExistentType() {
        // When
        Integer result = beanFactory.getBean(Integer.class);

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("Should work with complex objects")
    void testComplexObjects() {
        // Given
        TestComplexBean complexBean = new TestComplexBean("test", 42);
        String beanName = "complexBean";

        // When
        beanFactory.registerSingleton(beanName, complexBean);
        TestComplexBean retrieved = (TestComplexBean) beanFactory.getBean(beanName);

        // Then
        assertNotNull(retrieved);
        assertEquals("test", retrieved.getName());
        assertEquals(42, retrieved.getValue());
    }

    // Test helper class
    public static class TestComplexBean {
        private final String name;
        private final int value;

        public TestComplexBean(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }
    }
}

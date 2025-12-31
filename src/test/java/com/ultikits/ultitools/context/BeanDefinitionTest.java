package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BeanDefinition class.
 * <br>
 * BeanDefinition类的单元测试。
 */
@DisplayName("BeanDefinition Tests")
class BeanDefinitionTest {

    private BeanDefinition beanDefinition;

    @BeforeEach
    void setUp() {
        beanDefinition = new BeanDefinition();
    }

    @Test
    @DisplayName("Should create bean definition with default values")
    void testDefaultValues() {
        // Then
        assertNotNull(beanDefinition);
        assertEquals(SimpleContainer.BeanScope.SINGLETON, beanDefinition.getScope());
        assertNotNull(beanDefinition.getDependsOn());
        assertTrue(beanDefinition.getDependsOn().isEmpty());
        assertFalse(beanDefinition.isLazyInit());
        assertTrue(beanDefinition.isSingleton());
        assertFalse(beanDefinition.isPrototype());
    }

    @Test
    @DisplayName("Should create bean definition with bean class")
    void testConstructorWithBeanClass() {
        // Given
        Class<?> beanClass = TestService.class;

        // When
        BeanDefinition definition = new BeanDefinition(beanClass);

        // Then
        assertEquals(beanClass, definition.getBeanClass());
        assertEquals(SimpleContainer.BeanScope.SINGLETON, definition.getScope());
    }

    @Test
    @DisplayName("Should create bean definition with bean class and name")
    void testConstructorWithBeanClassAndName() {
        // Given
        Class<?> beanClass = TestService.class;
        String beanName = "testService";

        // When
        BeanDefinition definition = new BeanDefinition(beanClass, beanName);

        // Then
        assertEquals(beanClass, definition.getBeanClass());
        assertEquals(beanName, definition.getBeanName());
    }

    @Test
    @DisplayName("Should set and get bean class")
    void testBeanClass() {
        // Given
        Class<?> beanClass = String.class;

        // When
        beanDefinition.setBeanClass(beanClass);

        // Then
        assertEquals(beanClass, beanDefinition.getBeanClass());
    }

    @Test
    @DisplayName("Should set and get bean name")
    void testBeanName() {
        // Given
        String beanName = "myBean";

        // When
        beanDefinition.setBeanName(beanName);

        // Then
        assertEquals(beanName, beanDefinition.getBeanName());
    }

    @Test
    @DisplayName("Should set and get scope as SINGLETON")
    void testSingletonScope() {
        // When
        beanDefinition.setScope(SimpleContainer.BeanScope.SINGLETON);

        // Then
        assertEquals(SimpleContainer.BeanScope.SINGLETON, beanDefinition.getScope());
        assertTrue(beanDefinition.isSingleton());
        assertFalse(beanDefinition.isPrototype());
    }

    @Test
    @DisplayName("Should set and get scope as PROTOTYPE")
    void testPrototypeScope() {
        // When
        beanDefinition.setScope(SimpleContainer.BeanScope.PROTOTYPE);

        // Then
        assertEquals(SimpleContainer.BeanScope.PROTOTYPE, beanDefinition.getScope());
        assertFalse(beanDefinition.isSingleton());
        assertTrue(beanDefinition.isPrototype());
    }

    @Test
    @DisplayName("Should set and get instance")
    void testInstance() {
        // Given
        Object instance = new TestService();

        // When
        beanDefinition.setInstance(instance);

        // Then
        assertSame(instance, beanDefinition.getInstance());
    }

    @Test
    @DisplayName("Should set and get factory method")
    void testFactoryMethod() throws Exception {
        // Given
        Method method = TestFactory.class.getMethod("createService");

        // When
        beanDefinition.setFactoryMethod(method);

        // Then
        assertEquals(method, beanDefinition.getFactoryMethod());
    }

    @Test
    @DisplayName("Should set and get factory bean")
    void testFactoryBean() {
        // Given
        Object factoryBean = new TestFactory();

        // When
        beanDefinition.setFactoryBean(factoryBean);

        // Then
        assertSame(factoryBean, beanDefinition.getFactoryBean());
    }

    @Test
    @DisplayName("Should set and get dependsOn list")
    void testDependsOn() {
        // Given
        List<String> dependencies = Arrays.asList("bean1", "bean2", "bean3");

        // When
        beanDefinition.setDependsOn(dependencies);

        // Then
        assertEquals(dependencies, beanDefinition.getDependsOn());
        assertEquals(3, beanDefinition.getDependsOn().size());
    }

    @Test
    @DisplayName("Should add dependencies incrementally")
    void testAddDependenciesIncrementally() {
        // When
        beanDefinition.getDependsOn().add("dependency1");
        beanDefinition.getDependsOn().add("dependency2");

        // Then
        assertEquals(2, beanDefinition.getDependsOn().size());
        assertTrue(beanDefinition.getDependsOn().contains("dependency1"));
        assertTrue(beanDefinition.getDependsOn().contains("dependency2"));
    }

    @Test
    @DisplayName("Should set and get lazy init flag")
    void testLazyInit() {
        // When - default is false
        assertFalse(beanDefinition.isLazyInit());

        // When - set to true
        beanDefinition.setLazyInit(true);

        // Then
        assertTrue(beanDefinition.isLazyInit());

        // When - set back to false
        beanDefinition.setLazyInit(false);

        // Then
        assertFalse(beanDefinition.isLazyInit());
    }

    @Test
    @DisplayName("Should set and get constructor arguments")
    void testConstructorArguments() {
        // Given
        Object[] args = new Object[]{"arg1", 42, true};

        // When
        beanDefinition.setConstructorArgValues(args);

        // Then
        assertArrayEquals(args, beanDefinition.getConstructorArgValues());
        assertEquals(3, beanDefinition.getConstructorArgValues().length);
    }

    @Test
    @DisplayName("Should handle empty constructor arguments")
    void testEmptyConstructorArguments() {
        // Given
        Object[] emptyArgs = new Object[0];

        // When
        beanDefinition.setConstructorArgValues(emptyArgs);

        // Then
        assertNotNull(beanDefinition.getConstructorArgValues());
        assertEquals(0, beanDefinition.getConstructorArgValues().length);
    }

    @Test
    @DisplayName("Should handle null constructor arguments")
    void testNullConstructorArguments() {
        // When
        beanDefinition.setConstructorArgValues(null);

        // Then
        assertNull(beanDefinition.getConstructorArgValues());
    }

    @Test
    @DisplayName("Should correctly identify singleton vs prototype")
    void testScopeIdentification() {
        // Initially singleton
        assertTrue(beanDefinition.isSingleton());
        assertFalse(beanDefinition.isPrototype());

        // Change to prototype
        beanDefinition.setScope(SimpleContainer.BeanScope.PROTOTYPE);
        assertFalse(beanDefinition.isSingleton());
        assertTrue(beanDefinition.isPrototype());

        // Change back to singleton
        beanDefinition.setScope(SimpleContainer.BeanScope.SINGLETON);
        assertTrue(beanDefinition.isSingleton());
        assertFalse(beanDefinition.isPrototype());
    }

    @Test
    @DisplayName("Should create complete bean definition")
    void testCompleteBeanDefinition() throws Exception {
        // Given
        Class<?> beanClass = TestService.class;
        String beanName = "testService";
        Object instance = new TestService();
        Method factoryMethod = TestFactory.class.getMethod("createService");
        Object factoryBean = new TestFactory();
        List<String> dependencies = Arrays.asList("dep1", "dep2");
        Object[] constructorArgs = new Object[]{"arg1"};

        // When
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setBeanName(beanName);
        beanDefinition.setScope(SimpleContainer.BeanScope.PROTOTYPE);
        beanDefinition.setInstance(instance);
        beanDefinition.setFactoryMethod(factoryMethod);
        beanDefinition.setFactoryBean(factoryBean);
        beanDefinition.setDependsOn(dependencies);
        beanDefinition.setLazyInit(true);
        beanDefinition.setConstructorArgValues(constructorArgs);

        // Then
        assertEquals(beanClass, beanDefinition.getBeanClass());
        assertEquals(beanName, beanDefinition.getBeanName());
        assertEquals(SimpleContainer.BeanScope.PROTOTYPE, beanDefinition.getScope());
        assertSame(instance, beanDefinition.getInstance());
        assertEquals(factoryMethod, beanDefinition.getFactoryMethod());
        assertSame(factoryBean, beanDefinition.getFactoryBean());
        assertEquals(dependencies, beanDefinition.getDependsOn());
        assertTrue(beanDefinition.isLazyInit());
        assertArrayEquals(constructorArgs, beanDefinition.getConstructorArgValues());
    }

    // Test helper classes
    private static class TestService {
        private String name = "TestService";

        public String getName() {
            return name;
        }
    }

    private static class TestFactory {
        public TestService createService() {
            return new TestService();
        }
    }
}

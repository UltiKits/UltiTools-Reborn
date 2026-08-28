package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.exceptions.ContainerException;

/**
 * Comprehensive tests for SimpleContainer covering all functionality.
 * <br>
 * SimpleContainer的全面测试，覆盖所有功能。
 */
@DisplayName("SimpleContainer Comprehensive Tests")
class SimpleContainerComprehensiveTest {

    private SimpleContainer container;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
    }

    @Nested
    @DisplayName("Lifecycle Callback Tests")
    class LifecycleCallbackTests {

        @Test
        @DisplayName("Should invoke @PostConstruct method after bean creation")
        void testPostConstructInvocation() {
            // When
            container.registerBean(LifecycleService.class);
            LifecycleService service = container.getBean(LifecycleService.class);

            // Then
            assertNotNull(service);
            assertTrue(service.isPostConstructCalled());
            assertFalse(service.isPreDestroyCalled());
        }

        @Test
        @DisplayName("Should invoke @PreDestroy method on container close")
        void testPreDestroyInvocation() {
            // Given
            container.registerBean(LifecycleService.class);
            LifecycleService service = container.getBean(LifecycleService.class);
            assertTrue(service.isPostConstructCalled());

            // When
            container.close();

            // Then
            assertTrue(service.isPreDestroyCalled());
        }

        @Test
        @DisplayName("Should invoke lifecycle methods in correct order")
        void testLifecycleOrder() {
            // When
            container.registerBean(OrderedLifecycleService.class);
            OrderedLifecycleService service = container.getBean(OrderedLifecycleService.class);
            container.close();

            // Then
            List<String> order = service.getCallOrder();
            assertEquals(2, order.size());
            assertEquals("postConstruct", order.get(0));
            assertEquals("preDestroy", order.get(1));
        }

        @Test
        @DisplayName("Should invoke multiple @PostConstruct methods")
        void testMultiplePostConstructMethods() {
            // When
            container.registerBean(MultipleLifecycleService.class);
            MultipleLifecycleService service = container.getBean(MultipleLifecycleService.class);

            // Then
            assertTrue(service.isInit1Called());
            assertTrue(service.isInit2Called());
        }

        @Test
        @DisplayName("Should invoke inherited @PostConstruct methods")
        void testInheritedPostConstruct() {
            // When
            container.registerBean(ChildLifecycleService.class);
            ChildLifecycleService service = container.getBean(ChildLifecycleService.class);

            // Then
            assertTrue(service.isParentPostConstructCalled());
            assertTrue(service.isChildPostConstructCalled());
        }
    }

    @Nested
    @DisplayName("Constructor Matching Tests")
    class ConstructorMatchingTests {

        @Test
        @DisplayName("Should match constructor with interface parameter")
        void testInterfaceParameterMatching() {
            // Given
            List<String> items = new ArrayList<>();
            items.add("test");

            // When
            container.registerBean(ListConsumerService.class, items);
            ListConsumerService service = container.getBean(ListConsumerService.class);

            // Then
            assertNotNull(service);
            assertEquals(1, service.getItemCount());
        }

        @Test
        @DisplayName("Should match constructor with Integer wrapper")
        void testPrimitiveWrapperMatching() {
            // Given
            Integer value = 42;

            // When
            container.registerBean(IntegerService.class, value);
            IntegerService service = container.getBean(IntegerService.class);

            // Then
            assertNotNull(service);
            assertEquals(42, service.getValue());
        }

        @Test
        @DisplayName("Should match constructor with superclass parameter")
        void testSuperclassParameterMatching() {
            // Given
            StringBuilder sb = new StringBuilder("test");

            // When
            container.registerBean(CharSequenceService.class, sb);
            CharSequenceService service = container.getBean(CharSequenceService.class);

            // Then
            assertNotNull(service);
            assertEquals("test", service.getContent());
        }

        @Test
        @DisplayName("Should handle null parameter")
        void testNullParameterMatching() {
            // When
            container.registerBean(NullableService.class, (String) null);
            NullableService service = container.getBean(NullableService.class);

            // Then
            assertNotNull(service);
            assertNull(service.getValue());
        }

        @Test
        @DisplayName("Should match constructor with multiple parameters")
        void testMultipleParameterMatching() {
            // Given
            String name = "test";
            Integer count = 5;

            // When
            container.registerBean(MultiParamService.class, name, count);
            MultiParamService service = container.getBean(MultiParamService.class);

            // Then
            assertNotNull(service);
            assertEquals("test", service.getName());
            assertEquals(5, service.getCount());
        }
    }

    @Nested
    @DisplayName("getBeanNamesForType Optimization Tests")
    class GetBeanNamesForTypeTests {

        @Test
        @DisplayName("Should find beans from bean definitions without instantiation")
        void testBeanNamesFromDefinitions() {
            // Given
            container.registerBean(TestServiceA.class);
            container.registerBean(TestServiceB.class);

            // When - should not trigger instantiation
            String[] names = container.getBeanNamesForType(TestServiceA.class);

            // Then
            assertEquals(1, names.length);
            assertEquals("testServiceA", names[0]);
        }

        @Test
        @DisplayName("Should find beans from supplier types cache")
        void testBeanNamesFromSupplierTypes() {
            // Given
            container.registerSupplier("myString", () -> "test", String.class);

            // When
            String[] names = container.getBeanNamesForType(String.class);

            // Then
            assertEquals(1, names.length);
            assertEquals("myString", names[0]);
        }

        @Test
        @DisplayName("Should find beans from parent container")
        void testBeanNamesFromParent() {
            // Given
            SimpleContainer parent = new SimpleContainer();
            parent.registerBean(TestServiceA.class);
            container.setParent(parent);

            // When
            String[] names = container.getBeanNamesForType(TestServiceA.class);

            // Then
            assertEquals(1, names.length);
        }

        @Test
        @DisplayName("Should combine beans from all sources")
        void testBeanNamesFromAllSources() {
            // Given
            container.registerSingleton("singleton", "value");
            container.registerBean(TestServiceA.class);
            container.registerSupplier("supplier", () -> "test", String.class);

            // When
            String[] stringNames = container.getBeanNamesForType(String.class);

            // Then
            assertEquals(2, stringNames.length); // singleton + supplier
        }
    }

    @Nested
    @DisplayName("Prototype Scope Tests")
    class PrototypeScopeTests {

        @Test
        @DisplayName("Should create new instance for prototype beans")
        void testPrototypeBeanCreation() {
            // Given
            BeanDefinition definition = new BeanDefinition(TestServiceA.class, "prototypeService");
            definition.setScope(SimpleContainer.BeanScope.PROTOTYPE);
            container.registerBeanDefinition("prototypeService", definition);

            // When
            TestServiceA instance1 = (TestServiceA) container.getBean("prototypeService");
            TestServiceA instance2 = (TestServiceA) container.getBean("prototypeService");

            // Then
            assertNotNull(instance1);
            assertNotNull(instance2);
            assertNotSame(instance1, instance2);
        }

        @Test
        @DisplayName("Should correctly identify prototype beans")
        void testIsPrototype() {
            // Given
            BeanDefinition definition = new BeanDefinition(TestServiceA.class, "prototypeService");
            definition.setScope(SimpleContainer.BeanScope.PROTOTYPE);
            container.registerBeanDefinition("prototypeService", definition);

            // Then
            assertTrue(container.isPrototype("prototypeService"));
            assertFalse(container.isSingleton("prototypeService"));
        }
    }

    @Nested
    @DisplayName("Lazy Initialization Tests")
    class LazyInitializationTests {

        @Test
        @DisplayName("Should not instantiate lazy beans on start")
        void testLazyBeanNotInstantiatedOnStart() {
            // Given
            LazyService.resetInstantiated();
            BeanDefinition definition = new BeanDefinition(LazyService.class, "lazyService");
            definition.setLazyInit(true);
            container.registerBeanDefinition("lazyService", definition);

            // When
            container.start();

            // Then - bean should not be instantiated yet
            assertFalse(LazyService.isInstantiated(), "Lazy bean should not be instantiated on start");
            assertTrue(container.containsBean("lazyService"), "Container should contain lazy bean definition");

            // When retrieved
            container.getBean("lazyService");

            // Then
            assertTrue(LazyService.isInstantiated(), "Lazy bean should be instantiated after retrieval");
        }

        @Test
        @DisplayName("Should instantiate non-lazy beans on start")
        void testNonLazyBeanInstantiatedOnStart() {
            // Given
            container.registerBean(TestServiceA.class);

            // When
            container.start();

            // Then
            assertTrue(container.containsBean("testServiceA"));
        }
    }

    @Nested
    @DisplayName("getBeansOfType Tests")
    class GetBeansOfTypeTests {

        @Test
        @DisplayName("Should return all beans of specified type")
        void testGetBeansOfType() {
            // Given
            container.registerSingleton("service1", new TestServiceA());
            container.registerSingleton("service2", new TestServiceB());
            container.registerSingleton("string1", "test");

            // When
            Map<String, TestServiceA> serviceABeans = container.getBeansOfType(TestServiceA.class);

            // Then
            assertEquals(1, serviceABeans.size());
            assertTrue(serviceABeans.containsKey("service1"));
        }

        @Test
        @DisplayName("Should include beans from definitions")
        void testGetBeansOfTypeFromDefinitions() {
            // Given
            container.registerBean(TestServiceA.class);
            container.registerBean(TestServiceB.class);

            // When
            Map<String, TestServiceA> beans = container.getBeansOfType(TestServiceA.class);

            // Then
            assertEquals(1, beans.size());
        }
    }

    @Nested
    @DisplayName("getType Tests")
    class GetTypeTests {

        @Test
        @DisplayName("Should return type from beanTypes map")
        void testGetTypeFromBeanTypes() {
            // Given
            container.registerBean(TestServiceA.class);

            // When
            Class<?> type = container.getType("testServiceA");

            // Then
            assertEquals(TestServiceA.class, type);
        }

        @Test
        @DisplayName("Should return type from singleton instance")
        void testGetTypeFromSingleton() {
            // Given
            TestServiceA instance = new TestServiceA();
            container.registerSingleton("myService", instance);

            // When
            Class<?> type = container.getType("myService");

            // Then
            assertEquals(TestServiceA.class, type);
        }

        @Test
        @DisplayName("Should return null for non-existent bean")
        void testGetTypeForNonExistentBean() {
            // When
            Class<?> type = container.getType("nonExistent");

            // Then
            assertNull(type);
        }
    }

    @Nested
    @DisplayName("Parent Container Tests")
    class ParentContainerTests {

        @Test
        @DisplayName("Should search parent container for beans")
        void testParentContainerSearch() {
            // Given
            SimpleContainer parent = new SimpleContainer();
            parent.registerSingleton("parentBean", "parentValue");
            container.setParent(parent);

            // When
            Object bean = container.getBean("parentBean");

            // Then
            assertEquals("parentValue", bean);
        }

        @Test
        @DisplayName("Should prefer child container beans over parent")
        void testChildOverridesParent() {
            // Given
            SimpleContainer parent = new SimpleContainer();
            parent.registerSingleton("sharedBean", "parentValue");
            container.setParent(parent);
            container.registerSingleton("sharedBean", "childValue");

            // When
            Object bean = container.getBean("sharedBean");

            // Then
            assertEquals("childValue", bean);
        }

        @Test
        @DisplayName("Should check parent in containsBean")
        void testContainsBeanWithParent() {
            // Given
            SimpleContainer parent = new SimpleContainer();
            parent.registerSingleton("parentBean", "value");
            container.setParent(parent);

            // Then
            assertTrue(container.containsBean("parentBean"));
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("Should create bean using factory method")
        void testFactoryMethodCreation() throws Exception {
            // Given
            TestFactory factory = new TestFactory();
            BeanDefinition definition = new BeanDefinition(TestServiceA.class, "factoryCreated");
            definition.setFactoryBean(factory);
            definition.setFactoryMethod(TestFactory.class.getMethod("createService"));
            container.registerBeanDefinition("factoryCreated", definition);

            // When
            TestServiceA service = (TestServiceA) container.getBean("factoryCreated");

            // Then
            assertNotNull(service);
        }
    }

    @Nested
    @DisplayName("ClassLoader Tests")
    class ClassLoaderTests {

        @Test
        @DisplayName("Should use custom classloader when set")
        void testCustomClassLoader() {
            // Given
            ClassLoader customLoader = new ClassLoader() {};
            container.setClassLoader(customLoader);

            // Then
            assertEquals(customLoader, container.getClassLoader());
        }
    }

    @Nested
    @DisplayName("getBeanDefinitionNames Tests")
    class GetBeanDefinitionNamesTests {

        @Test
        @DisplayName("Should return all bean names")
        void testGetBeanDefinitionNames() {
            // Given
            container.registerSingleton("singleton1", "value1");
            container.registerSupplier("supplier1", () -> "value2");
            container.registerBean(TestServiceA.class);

            // When
            String[] names = container.getBeanDefinitionNames();

            // Then
            assertEquals(3, names.length);
        }
    }

    @Nested
    @DisplayName("Constructor Injection Failure Tests")
    class ConstructorInjectionFailureTests {

        @Test
        @DisplayName("Should throw ContainerException when the bean class has no usable constructor")
        void testNoUsableConstructorThrows() {
            // Given - an interface has no constructors at all, so
            // createBeanWithConstructorInjection's best-constructor search comes up empty
            container.registerBean(NoConstructorInterface.class);

            // When
            ContainerException exception = assertThrows(ContainerException.class,
                    () -> container.getBean(NoConstructorInterface.class));

            // Then
            assertTrue(exception.getMessage().contains("No constructor found for: "));
            assertTrue(exception.getMessage().contains(NoConstructorInterface.class.getName()));
        }

        @Test
        @DisplayName("Should throw ContainerException when a constructor parameter cannot be resolved")
        void testUnresolvableConstructorParameterThrows() {
            // Given - UnresolvableConstructorDependency is never registered
            container.registerBean(UnresolvableConstructorBean.class);

            // When
            ContainerException exception = assertThrows(ContainerException.class,
                    () -> container.getBean(UnresolvableConstructorBean.class));

            // Then
            assertTrue(exception.getMessage().contains(UnresolvableConstructorDependency.class.getName()));
            assertTrue(exception.getMessage().contains(UnresolvableConstructorBean.class.getName()));
        }

        @Test
        @DisplayName("Should surface the original throwable as the cause when the constructor itself throws")
        void testThrowingConstructorPreservesCause() {
            // Given - the dependency resolves, but the constructor body throws
            container.registerType(SimpleConstructorDependency.class, new SimpleConstructorDependency());
            container.registerBean(ThrowingConstructorBean.class);

            // When
            ContainerException exception = assertThrows(ContainerException.class,
                    () -> container.getBean(ThrowingConstructorBean.class));

            // Then
            assertNotNull(exception.getCause(),
                    "the cause is the only pointer to what actually failed inside the constructor");
        }

        @Test
        @DisplayName("Should create the bean normally when all constructor parameters resolve")
        void testResolvableConstructorSucceeds() {
            // Given
            container.registerType(SimpleConstructorDependency.class, new SimpleConstructorDependency());
            container.registerBean(ResolvableConstructorBean.class);

            // When
            ResolvableConstructorBean bean = container.getBean(ResolvableConstructorBean.class);

            // Then
            assertNotNull(bean);
            assertNotNull(bean.getDependency());
        }
    }

    // Test helper classes
    public static class LifecycleService {
        private boolean postConstructCalled = false;
        private boolean preDestroyCalled = false;

        @PostConstruct
        public void init() {
            postConstructCalled = true;
        }

        @PreDestroy
        public void cleanup() {
            preDestroyCalled = true;
        }

        public boolean isPostConstructCalled() {
            return postConstructCalled;
        }

        public boolean isPreDestroyCalled() {
            return preDestroyCalled;
        }
    }

    public static class OrderedLifecycleService {
        private final List<String> callOrder = new ArrayList<>();

        @PostConstruct
        public void init() {
            callOrder.add("postConstruct");
        }

        @PreDestroy
        public void cleanup() {
            callOrder.add("preDestroy");
        }

        public List<String> getCallOrder() {
            return callOrder;
        }
    }

    public static class MultipleLifecycleService {
        private boolean init1Called = false;
        private boolean init2Called = false;

        @PostConstruct
        public void init1() {
            init1Called = true;
        }

        @PostConstruct
        public void init2() {
            init2Called = true;
        }

        public boolean isInit1Called() {
            return init1Called;
        }

        public boolean isInit2Called() {
            return init2Called;
        }
    }

    public static class ParentLifecycleService {
        private boolean parentPostConstructCalled = false;

        @PostConstruct
        public void parentInit() {
            parentPostConstructCalled = true;
        }

        public boolean isParentPostConstructCalled() {
            return parentPostConstructCalled;
        }
    }

    public static class ChildLifecycleService extends ParentLifecycleService {
        private boolean childPostConstructCalled = false;

        @PostConstruct
        public void childInit() {
            childPostConstructCalled = true;
        }

        public boolean isChildPostConstructCalled() {
            return childPostConstructCalled;
        }
    }

    public static class ListConsumerService {
        private final List<?> items;

        public ListConsumerService(List<?> items) {
            this.items = items;
        }

        public int getItemCount() {
            return items.size();
        }
    }

    public static class IntegerService {
        private final int value;

        public IntegerService(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public static class CharSequenceService {
        private final CharSequence content;

        public CharSequenceService(CharSequence content) {
            this.content = content;
        }

        public String getContent() {
            return content.toString();
        }
    }

    public static class NullableService {
        private final String value;

        public NullableService(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public static class MultiParamService {
        private final String name;
        private final int count;

        public MultiParamService(String name, Integer count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }

    public static class TestServiceA {
        public String getName() {
            return "TestServiceA";
        }
    }

    public static class TestServiceB {
        public String getName() {
            return "TestServiceB";
        }
    }

    public static class LazyService {
        private static final Object INSTANTIATED_LOCK = new Object();
        private static volatile boolean instantiated = false;

        public LazyService() {
            synchronized (INSTANTIATED_LOCK) {
                instantiated = true;
            }
        }

        public static boolean isInstantiated() {
            synchronized (INSTANTIATED_LOCK) {
                return instantiated;
            }
        }

        public static void resetInstantiated() {
            synchronized (INSTANTIATED_LOCK) {
                instantiated = false;
            }
        }
    }

    public static class TestFactory {
        public TestServiceA createService() {
            return new TestServiceA();
        }
    }

    // Fixtures for ConstructorInjectionFailureTests -- exercise
    // createBeanWithConstructorInjection through the full container.getBean(Class) path.
    public interface NoConstructorInterface {
        // Interfaces have zero declared constructors, so this reaches the
        // "no usable constructor" branch of createBeanWithConstructorInjection.
    }

    public static class UnresolvableConstructorDependency {
        // Deliberately never registered with the container.
    }

    public static class UnresolvableConstructorBean {
        public UnresolvableConstructorBean(UnresolvableConstructorDependency dependency) {
            // Never reached: the dependency cannot be resolved.
        }
    }

    public static class SimpleConstructorDependency {
        // A plain, resolvable dependency.
    }

    public static class ThrowingConstructorBean {
        public ThrowingConstructorBean(SimpleConstructorDependency dependency) {
            throw new IllegalStateException("constructor body deliberately fails");
        }
    }

    public static class ResolvableConstructorBean {
        private final SimpleConstructorDependency dependency;

        public ResolvableConstructorBean(SimpleConstructorDependency dependency) {
            this.dependency = dependency;
        }

        public SimpleConstructorDependency getDependency() {
            return dependency;
        }
    }
}

package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Component;

/**
 * Tests for three-level cache circular dependency resolution in SimpleContainer.
 */
@DisplayName("SimpleContainer Three-Level Cache Tests")
class SimpleContainerThreeLevelCacheTest {

    private SimpleContainer container;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
    }

    // Test beans for circular dependency scenarios
    @Component
    public static class ServiceA {
        @Autowired
        private ServiceB serviceB;

        public ServiceB getServiceB() {
            return serviceB;
        }

        public void setServiceB(ServiceB serviceB) {
            this.serviceB = serviceB;
        }
    }

    @Component
    public static class ServiceB {
        @Autowired
        private ServiceA serviceA;

        public ServiceA getServiceA() {
            return serviceA;
        }

        public void setServiceA(ServiceA serviceA) {
            this.serviceA = serviceA;
        }
    }

    @Component
    public static class ServiceC {
        @Autowired
        private ServiceD serviceD;

        @Autowired
        private ServiceE serviceE;

        public ServiceD getServiceD() {
            return serviceD;
        }

        public ServiceE getServiceE() {
            return serviceE;
        }
    }

    @Component
    public static class ServiceD {
        @Autowired
        private ServiceE serviceE;

        public ServiceE getServiceE() {
            return serviceE;
        }
    }

    @Component
    public static class ServiceE {
        // No dependencies
    }

    // Three-way circular dependency
    @Component
    public static class NodeA {
        @Autowired
        private NodeB nodeB;

        public NodeB getNodeB() {
            return nodeB;
        }
    }

    @Component
    public static class NodeB {
        @Autowired
        private NodeC nodeC;

        public NodeC getNodeC() {
            return nodeC;
        }
    }

    @Component
    public static class NodeC {
        @Autowired
        private NodeA nodeA;

        public NodeA getNodeA() {
            return nodeA;
        }
    }

    @Nested
    @DisplayName("Basic Cache Operations")
    class BasicCacheTests {

        @Test
        @DisplayName("getSingleton should return null for non-existent bean")
        void getSingletonReturnsNullForNonExistent() {
            Object result = container.getSingleton("nonExistent", true);
            assertNull(result);
        }

        @Test
        @DisplayName("addSingleton should store bean in level 1 cache")
        void addSingletonStoresInLevel1() {
            Object bean = new Object();
            container.addSingleton("testBean", bean);

            Object result = container.getSingleton("testBean", false);
            assertSame(bean, result);
        }

        @Test
        @DisplayName("registerSingleton should use addSingleton internally")
        void registerSingletonUsesAddSingleton() {
            Object bean = new Object();
            container.registerSingleton("testBean", bean);

            Object result = container.getBean("testBean");
            assertSame(bean, result);
        }
    }

    @Nested
    @DisplayName("Singleton Factory Tests")
    class SingletonFactoryTests {

        @Test
        @DisplayName("addSingletonFactory should store factory in level 3 cache")
        void addSingletonFactoryStoresInLevel3() {
            Object bean = new Object();
            container.addSingletonFactory("testBean", () -> bean);

            // Factory should not be retrieved without being in currentlyCreating
            Object result = container.getSingleton("testBean", true);
            assertNull(result);
        }

        @Test
        @DisplayName("addSingleton should clear singleton factory")
        void addSingletonClearsSingletonFactory() {
            Object bean = new Object();
            container.addSingletonFactory("testBean", () -> new Object());
            container.addSingleton("testBean", bean);

            Object result = container.getSingleton("testBean", true);
            assertSame(bean, result);
        }
    }

    @Nested
    @DisplayName("Bean Creation with Cache")
    class BeanCreationTests {

        @Test
        @DisplayName("should create singleton bean and store in cache")
        void shouldCreateAndStoreSingleton() {
            BeanDefinition definition = new BeanDefinition(ServiceE.class);
            container.registerBeanDefinition("serviceE", definition);

            Object bean1 = container.getBean("serviceE");
            Object bean2 = container.getBean("serviceE");

            assertNotNull(bean1);
            assertSame(bean1, bean2, "Should return same instance for singleton");
        }

        @Test
        @DisplayName("should handle diamond dependency pattern")
        void shouldHandleDiamondDependency() {
            // ServiceC depends on ServiceD and ServiceE
            // ServiceD also depends on ServiceE
            // This is a diamond pattern but not circular

            container.registerBeanDefinition("serviceE", new BeanDefinition(ServiceE.class));
            container.registerBeanDefinition("serviceD", new BeanDefinition(ServiceD.class));
            container.registerBeanDefinition("serviceC", new BeanDefinition(ServiceC.class));

            ServiceC serviceC = (ServiceC) container.getBean("serviceC");

            assertNotNull(serviceC);
            assertNotNull(serviceC.getServiceD());
            assertNotNull(serviceC.getServiceE());
            assertNotNull(serviceC.getServiceD().getServiceE());

            // ServiceE should be the same instance everywhere
            assertSame(serviceC.getServiceE(), serviceC.getServiceD().getServiceE());
        }
    }

    @Nested
    @DisplayName("Circular Dependency Resolution")
    class CircularDependencyTests {

        @Test
        @DisplayName("should resolve two-way setter injection circular dependency")
        void shouldResolveTwoWayCircularDependency() {
            container.registerBeanDefinition("serviceA", new BeanDefinition(ServiceA.class));
            container.registerBeanDefinition("serviceB", new BeanDefinition(ServiceB.class));

            // This should not throw - circular dependency resolved via three-level cache
            ServiceA serviceA = (ServiceA) container.getBean("serviceA");
            ServiceB serviceB = (ServiceB) container.getBean("serviceB");

            assertNotNull(serviceA);
            assertNotNull(serviceB);
            assertNotNull(serviceA.getServiceB());
            assertNotNull(serviceB.getServiceA());
            assertSame(serviceB, serviceA.getServiceB());
            assertSame(serviceA, serviceB.getServiceA());
        }

        @Test
        @DisplayName("should resolve three-way setter injection circular dependency")
        void shouldResolveThreeWayCircularDependency() {
            container.registerBeanDefinition("nodeA", new BeanDefinition(NodeA.class));
            container.registerBeanDefinition("nodeB", new BeanDefinition(NodeB.class));
            container.registerBeanDefinition("nodeC", new BeanDefinition(NodeC.class));

            NodeA nodeA = (NodeA) container.getBean("nodeA");
            NodeB nodeB = (NodeB) container.getBean("nodeB");
            NodeC nodeC = (NodeC) container.getBean("nodeC");

            assertNotNull(nodeA);
            assertNotNull(nodeB);
            assertNotNull(nodeC);
            
            // Verify circular references are correct
            assertSame(nodeB, nodeA.getNodeB());
            assertSame(nodeC, nodeB.getNodeC());
            assertSame(nodeA, nodeC.getNodeA());
        }
    }

    @Nested
    @DisplayName("Prototype Bean Tests")
    class PrototypeBeanTests {

        @Test
        @DisplayName("prototype beans should not use singleton cache")
        void prototypeShouldNotUseSingletonCache() {
            BeanDefinition definition = new BeanDefinition(ServiceE.class);
            definition.setScope(SimpleContainer.BeanScope.PROTOTYPE);
            container.registerBeanDefinition("prototypeE", definition);

            Object bean1 = container.getBean("prototypeE");
            Object bean2 = container.getBean("prototypeE");

            assertNotNull(bean1);
            assertNotNull(bean2);
            assertNotSame(bean1, bean2, "Prototype should create new instances");
        }
    }

    @Nested
    @DisplayName("Container Close Tests")
    class ContainerCloseTests {

        @Test
        @DisplayName("close should clear all three caches")
        void closeShouldClearAllCaches() {
            Object bean = new Object();
            container.registerSingleton("testBean", bean);
            container.addSingletonFactory("factoryBean", Object::new);

            container.close();

            assertNull(container.getBean("testBean"));
            assertNull(container.getSingleton("factoryBean", true));
        }
    }
}

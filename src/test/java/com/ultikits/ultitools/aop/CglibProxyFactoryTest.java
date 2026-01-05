package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CglibProxyFactory.
 * 
 * Note: Tests that require actual CGLIB proxies are disabled by default because
 * CGLIB requires --add-opens JVM arguments on Java 17+. These tests can be enabled
 * by running with appropriate JVM args.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CglibProxyFactory Tests")
class CglibProxyFactoryTest {

    @Mock
    private MethodInterceptor mockInterceptor;

    // Test classes
    public static class SimpleTarget {
        public String getValue() {
            return "original";
        }

        public int calculate(int a, int b) {
            return a + b;
        }

        public void doNothing() {
            // no-op
        }
    }

    public static class TargetWithMultipleMethods {
        public String method1() {
            return "method1";
        }

        public String method2() {
            return "method2";
        }

        public String method3() {
            return "method3";
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create factory with interceptors")
        void shouldCreateFactoryWithInterceptors() {
            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            
            CglibProxyFactory factory = new CglibProxyFactory(interceptors);
            
            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with empty interceptor list")
        void shouldCreateFactoryWithEmptyInterceptorList() {
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            CglibProxyFactory factory = new CglibProxyFactory(interceptors);
            
            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with null interceptor list")
        void shouldCreateFactoryWithNullInterceptorList() {
            CglibProxyFactory factory = new CglibProxyFactory(null);
            
            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with multiple interceptors")
        void shouldCreateFactoryWithMultipleInterceptors() {
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor3 = mock(MethodInterceptor.class);
            
            List<MethodInterceptor> interceptors = Arrays.asList(interceptor1, interceptor2, interceptor3);
            CglibProxyFactory factory = new CglibProxyFactory(interceptors);
            
            assertNotNull(factory);
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("Should have createProxy with target method")
        void shouldHaveCreateProxyWithTargetMethod() throws NoSuchMethodException {
            Method method = CglibProxyFactory.class.getMethod("createProxy", Object.class);
            
            assertNotNull(method);
            assertEquals(Object.class, method.getReturnType());
        }

        @Test
        @DisplayName("Should have createProxy with class and target method")
        void shouldHaveCreateProxyWithClassAndTargetMethod() throws NoSuchMethodException {
            Method method = CglibProxyFactory.class.getMethod("createProxy", Class.class, Object.class);
            
            assertNotNull(method);
            assertEquals(Object.class, method.getReturnType());
        }
    }

    // The following tests require CGLIB to work properly.
    // On Java 17+, they need --add-opens java.base/java.lang=ALL-UNNAMED JVM argument.
    // These tests are disabled by default.
    
    @Nested
    @DisplayName("Proxy Creation Tests (requires CGLIB)")
    @Disabled("CGLIB requires --add-opens JVM args on Java 17+")
    class ProxyCreationTests {

        @Test
        @DisplayName("Should create proxy from target object")
        void shouldCreateProxyFromTargetObject() {
            SimpleTarget target = new SimpleTarget();
            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            CglibProxyFactory proxyFactory = new CglibProxyFactory(interceptors);

            SimpleTarget proxy = proxyFactory.createProxy(target);

            assertNotNull(proxy);
            assertNotSame(target, proxy);
        }

        @Test
        @DisplayName("Should proxy be instance of target class")
        void shouldProxyBeInstanceOfTargetClass() {
            SimpleTarget target = new SimpleTarget();
            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            CglibProxyFactory proxyFactory = new CglibProxyFactory(interceptors);

            SimpleTarget proxy = proxyFactory.createProxy(target);

            assertTrue(proxy instanceof SimpleTarget);
        }

        @Test
        @DisplayName("Should intercept method calls")
        void shouldInterceptMethodCalls() throws Throwable {
            SimpleTarget target = new SimpleTarget();
            
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(invocation -> {
                MethodInvocation mi = invocation.getArgument(0);
                return "intercepted:" + mi.proceed();
            });

            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            CglibProxyFactory proxyFactory = new CglibProxyFactory(interceptors);
            SimpleTarget proxy = proxyFactory.createProxy(target);

            String result = proxy.getValue();

            assertEquals("intercepted:original", result);
        }
    }
}

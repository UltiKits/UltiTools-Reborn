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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for ProxyFactory.
 * <p>
 * Unlike the CGLIB implementation these tests replaced, none of them require
 * --add-opens JVM arguments. See issue #188 for the decision record.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyFactory Tests")
class ProxyFactoryTest {

    @Mock
    private MethodInterceptor mockInterceptor;

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

            ProxyFactory factory = new ProxyFactory(interceptors);

            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with empty interceptor list")
        void shouldCreateFactoryWithEmptyInterceptorList() {
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with null interceptor list")
        void shouldCreateFactoryWithNullInterceptorList() {
            ProxyFactory factory = new ProxyFactory(null);

            assertNotNull(factory);
        }

        @Test
        @DisplayName("Should create factory with multiple interceptors")
        void shouldCreateFactoryWithMultipleInterceptors() {
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor3 = mock(MethodInterceptor.class);

            List<MethodInterceptor> interceptors = Arrays.asList(interceptor1, interceptor2, interceptor3);
            ProxyFactory factory = new ProxyFactory(interceptors);

            assertNotNull(factory);
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("Should have createProxy with target method")
        void shouldHaveCreateProxyWithTargetMethod() throws NoSuchMethodException {
            Method method = ProxyFactory.class.getMethod("createProxy", Object.class);

            assertNotNull(method);
            assertEquals(Object.class, method.getReturnType());
        }

        @Test
        @DisplayName("Should have createProxy with class and target method")
        void shouldHaveCreateProxyWithClassAndTargetMethod() throws NoSuchMethodException {
            Method method = ProxyFactory.class.getMethod("createProxy", Class.class, Object.class);

            assertNotNull(method);
            assertEquals(Object.class, method.getReturnType());
        }
    }

    @Nested
    @DisplayName("Proxy Creation Tests")
    class ProxyCreationTests {

        @Test
        @DisplayName("Should create proxy from target object")
        void shouldCreateProxyFromTargetObject() {
            SimpleTarget target = new SimpleTarget();
            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));

            SimpleTarget proxy = proxyFactory.createProxy(target);

            assertNotNull(proxy);
            assertNotSame(target, proxy);
        }

        @Test
        @DisplayName("Should proxy be instance of target class")
        void shouldProxyBeInstanceOfTargetClass() {
            SimpleTarget target = new SimpleTarget();
            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));

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

            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));
            SimpleTarget proxy = proxyFactory.createProxy(target);

            String result = proxy.getValue();

            assertEquals("intercepted:original", result);
        }

        @Test
        @DisplayName("Should pass primitive arguments and return values through")
        void shouldPassPrimitivesThrough() {
            SimpleTarget target = new SimpleTarget();
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());

            SimpleTarget proxy = proxyFactory.createProxy(target);

            assertEquals(5, proxy.calculate(2, 3));
        }

        @Test
        @DisplayName("Should proxy class with multiple methods")
        void shouldProxyClassWithMultipleMethods() {
            TargetWithMultipleMethods target = new TargetWithMultipleMethods();
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());

            TargetWithMultipleMethods proxy = proxyFactory.createProxy(target);

            assertEquals("method1", proxy.method1());
            assertEquals("method2", proxy.method2());
            assertEquals("method3", proxy.method3());
        }
    }
}

package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>
 * The proxy is inheritance-based (issue #190): {@code createProxyClass} returns a subclass, and
 * the caller instantiates it directly. There is no separate target instance to compare identity
 * against or forward {@code Object} methods to, which is exactly the delegating-proxy defect
 * this redesign removes - so the tests below exercise the generated class and its instances
 * directly instead of a target/proxy pair.
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

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface MarkerType { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface MarkerMethod { }

    public static class CountingTarget {
        static int instances = 0;
        public CountingTarget() { instances++; }
        public String work() { return "work"; }
    }

    public static class SelfCallTarget {
        public String outer() { return "outer:" + inner(); }
        public String inner() { return "inner"; }
    }

    public static class StatefulTarget {
        public final Map<String, String> cache = new HashMap<>();
        public void put() { cache.put("k", "v"); }
    }

    @MarkerType
    public static class AnnotatedTarget {
        @MarkerMethod
        public String annotated() { return "annotated"; }
    }

    public static class ThrowingTarget {
        public String checked() throws IOException { throw new IOException("checked-boom"); }
    }

    public static class GenericBase<T> {
        public T identity(T value) { return value; }
    }

    // Overriding a generic supertype method with a concrete type parameter makes javac emit a
    // synthetic bridge method (Object identity(Object)) alongside the real one - reflection APIs
    // like getMethods() return both. See shouldIgnoreBridgeMethods below.
    public static class StringIdentityTarget extends GenericBase<String> {
        @Override
        public String identity(String value) { return value; }
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
    @DisplayName("Inheritance-based proxy semantics")
    class InheritanceSemantics {

        @Test
        @DisplayName("Should produce a subclass whose instance is the only object")
        void shouldNotCreateSecondInstance() throws Exception {
            CountingTarget.instances = 0;
            Set<Method> intercepted = Collections.singleton(
                    CountingTarget.class.getMethod("work"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            Class<? extends CountingTarget> proxyClass =
                    factory.createProxyClass(CountingTarget.class, intercepted);
            CountingTarget bean = proxyClass.getDeclaredConstructor().newInstance();

            assertEquals(1, CountingTarget.instances,
                    "constructor must run exactly once: the proxy IS the bean");
            assertTrue(ProxyFactory.isProxyClass(bean.getClass()));
            assertTrue(CountingTarget.class.isInstance(bean));
        }

        @Test
        @DisplayName("Should intercept self-invocation")
        void shouldInterceptSelfInvocation() throws Exception {
            List<String> log = new ArrayList<>();
            MethodInterceptor recorder = inv -> {
                log.add(inv.getMethod().getName());
                return inv.proceed();
            };
            Set<Method> intercepted = new LinkedHashSet<>(Arrays.asList(
                    SelfCallTarget.class.getMethod("outer"),
                    SelfCallTarget.class.getMethod("inner")));
            ProxyFactory factory = new ProxyFactory(Collections.singletonList(recorder));

            SelfCallTarget bean = factory.createProxyClass(SelfCallTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            assertEquals("outer:inner", bean.outer());
            assertEquals(Arrays.asList("outer", "inner"), log,
                    "this.inner() must be intercepted, not bypassed");
        }

        @Test
        @DisplayName("Should keep fields on the single instance")
        void shouldKeepFieldsOnSingleInstance() throws Exception {
            Set<Method> intercepted = Collections.singleton(
                    StatefulTarget.class.getMethod("put"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            StatefulTarget bean = factory.createProxyClass(StatefulTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();
            bean.put();

            assertEquals("v", bean.cache.get("k"),
                    "no second object: the write must land on the bean the caller holds");
        }

        @Test
        @DisplayName("Should copy type and method annotations onto the proxy")
        void shouldCopyAnnotations() throws Exception {
            Set<Method> intercepted = Collections.singleton(
                    AnnotatedTarget.class.getMethod("annotated"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            Class<? extends AnnotatedTarget> proxyClass =
                    factory.createProxyClass(AnnotatedTarget.class, intercepted);

            assertNotNull(proxyClass.getAnnotation(MarkerType.class),
                    "type annotations are not @Inherited; they must be copied");
            assertNotNull(proxyClass.getMethod("annotated").getAnnotation(MarkerMethod.class),
                    "overriding methods do not inherit annotations; they must be copied");
        }

        @Test
        @DisplayName("Should ignore bridge methods even when passed in interceptedMethods (regression)")
        void shouldIgnoreBridgeMethods() throws Exception {
            Set<Method> allMethods = new LinkedHashSet<>(
                    Arrays.asList(StringIdentityTarget.class.getMethods()));
            assertTrue(allMethods.stream().anyMatch(Method::isBridge),
                    "test fixture must actually produce a bridge method, or this test proves nothing");
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            // MethodCall.invoke(bridgeMethod).onSuper() throws IllegalStateException at build
            // time if bridge methods aren't filtered out before generating trampolines - this must
            // not throw, and the real (non-bridge) method must still work normally.
            StringIdentityTarget bean = factory
                    .createProxyClass(StringIdentityTarget.class, allMethods)
                    .getDeclaredConstructor().newInstance();

            assertEquals("hello", bean.identity("hello"));
        }

        @Test
        @DisplayName("Should leave methods outside the intercepted set untouched")
        void shouldNotInterceptUnlistedMethods() throws Exception {
            List<String> log = new ArrayList<>();
            MethodInterceptor recorder = inv -> {
                log.add(inv.getMethod().getName());
                return inv.proceed();
            };
            Set<Method> intercepted = Collections.singleton(
                    SelfCallTarget.class.getMethod("inner"));
            ProxyFactory factory = new ProxyFactory(Collections.singletonList(recorder));

            SelfCallTarget bean = factory.createProxyClass(SelfCallTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();
            bean.outer();

            assertEquals(Collections.singletonList("inner"), log);
        }

        @Test
        @DisplayName("Should propagate checked exceptions unwrapped")
        void shouldPropagateCheckedExceptionUnwrapped() throws Exception {
            Set<Method> intercepted = Collections.singleton(
                    ThrowingTarget.class.getMethod("checked"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            ThrowingTarget bean = factory.createProxyClass(ThrowingTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            IOException thrown = assertThrows(IOException.class, bean::checked);
            assertEquals("checked-boom", thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Proxy Creation Tests")
    class ProxyCreationTests {

        @Test
        @DisplayName("Should create a proxy subclass distinct from the target class")
        void shouldCreateProxyFromTargetClass() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));

            Class<? extends SimpleTarget> proxyClass =
                    proxyFactory.createProxyClass(SimpleTarget.class, intercepted);
            SimpleTarget proxy = proxyClass.getDeclaredConstructor().newInstance();

            assertNotNull(proxy);
            assertNotEquals(SimpleTarget.class, proxy.getClass());
            assertTrue(ProxyFactory.isProxyClass(proxy.getClass()));
        }

        @Test
        @DisplayName("Should proxy be instance of target class")
        void shouldProxyBeInstanceOfTargetClass() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));

            SimpleTarget proxy = proxyFactory.createProxyClass(SimpleTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            assertTrue(proxy instanceof SimpleTarget);
        }

        @Test
        @DisplayName("Should intercept method calls")
        void shouldInterceptMethodCalls() throws Throwable {
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(invocation -> {
                MethodInvocation mi = invocation.getArgument(0);
                return "intercepted:" + mi.proceed();
            });

            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory proxyFactory = new ProxyFactory(Collections.singletonList(mockInterceptor));
            SimpleTarget proxy = proxyFactory.createProxyClass(SimpleTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            String result = proxy.getValue();

            assertEquals("intercepted:original", result);
        }

        @Test
        @DisplayName("Should pass primitive arguments and return values through")
        void shouldPassPrimitivesThrough() throws Exception {
            Set<Method> intercepted = Collections.singleton(
                    SimpleTarget.class.getMethod("calculate", int.class, int.class));
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());

            SimpleTarget proxy = proxyFactory.createProxyClass(SimpleTarget.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            assertEquals(5, proxy.calculate(2, 3));
        }

        @Test
        @DisplayName("Should proxy class with multiple methods")
        void shouldProxyClassWithMultipleMethods() throws Exception {
            Set<Method> intercepted = new LinkedHashSet<>(Arrays.asList(
                    TargetWithMultipleMethods.class.getMethod("method1"),
                    TargetWithMultipleMethods.class.getMethod("method2"),
                    TargetWithMultipleMethods.class.getMethod("method3")));
            ProxyFactory proxyFactory = new ProxyFactory(Collections.emptyList());

            TargetWithMultipleMethods proxy =
                    proxyFactory.createProxyClass(TargetWithMultipleMethods.class, intercepted)
                            .getDeclaredConstructor().newInstance();

            assertEquals("method1", proxy.method1());
            assertEquals("method2", proxy.method2());
            assertEquals("method3", proxy.method3());
        }
    }

    @Nested
    @DisplayName("Proxy identity (@ProxyOf marker)")
    class ProxyIdentityTests {

        @Test
        @DisplayName("Should attach @ProxyOf naming the target on the generated class")
        void shouldAttachProxyOfMarkerNamingTheTarget() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            Class<? extends SimpleTarget> proxyClass =
                    factory.createProxyClass(SimpleTarget.class, intercepted);

            ProxyOf marker = proxyClass.getAnnotation(ProxyOf.class);
            assertNotNull(marker, "a generated proxy must carry @ProxyOf");
            assertEquals(SimpleTarget.class, marker.value());
        }

        @Test
        @DisplayName("Should recognise only an actual proxy: not the target, not Object, not null")
        void shouldRecognizeOnlyAnActualProxy() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Class<? extends SimpleTarget> proxyClass =
                    factory.createProxyClass(SimpleTarget.class, intercepted);

            assertTrue(ProxyFactory.isProxyClass(proxyClass));
            assertFalse(ProxyFactory.isProxyClass(SimpleTarget.class));
            assertFalse(ProxyFactory.isProxyClass(Object.class));
            assertFalse(ProxyFactory.isProxyClass(null));
        }

        @Test
        @DisplayName("Should unwrap a proxy to its target, and leave a non-proxy and null unchanged")
        void shouldUnwrapProxyToTarget() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Class<? extends SimpleTarget> proxyClass =
                    factory.createProxyClass(SimpleTarget.class, intercepted);

            assertEquals(SimpleTarget.class, ProxyFactory.unwrap(proxyClass));
            assertEquals(SimpleTarget.class, ProxyFactory.unwrap(SimpleTarget.class));
            assertNull(ProxyFactory.unwrap(null));
        }

        @Test
        @DisplayName("Should carry exactly one @ProxyOf, naming the original target, when proxying a proxy")
        void shouldCarryExactlyOneMarkerWhenProxyingAProxy() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Class<? extends SimpleTarget> proxyClass =
                    factory.createProxyClass(SimpleTarget.class, intercepted);

            @SuppressWarnings("unchecked")
            Class<? extends SimpleTarget> proxyOfProxyClass =
                    (Class<? extends SimpleTarget>) factory.createProxyClass(proxyClass, intercepted);

            ProxyOf[] markers = proxyOfProxyClass.getAnnotationsByType(ProxyOf.class);
            assertEquals(1, markers.length,
                    "proxying an already-proxied class must not duplicate the marker");
            assertEquals(SimpleTarget.class, markers[0].value(),
                    "the marker must name the original target, not the intermediate proxy");
        }

        @Test
        @DisplayName("Should unwrap a proxy of a proxy to the original target in a single step")
        void shouldUnwrapProxyOfProxyInOneStep() throws Exception {
            Set<Method> intercepted = Collections.singleton(SimpleTarget.class.getMethod("getValue"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Class<? extends SimpleTarget> proxyClass =
                    factory.createProxyClass(SimpleTarget.class, intercepted);

            @SuppressWarnings("unchecked")
            Class<? extends SimpleTarget> proxyOfProxyClass =
                    (Class<? extends SimpleTarget>) factory.createProxyClass(proxyClass, intercepted);

            assertEquals(SimpleTarget.class, ProxyFactory.unwrap(proxyOfProxyClass),
                    "a proxy of a proxy must resolve straight to the original target");
        }

        @Test
        @DisplayName("Should keep unrelated type annotations on the proxy alongside @ProxyOf")
        void shouldPreserveUnrelatedAnnotationsAlongsideMarker() throws Exception {
            Set<Method> intercepted = Collections.singleton(
                    AnnotatedTarget.class.getMethod("annotated"));
            ProxyFactory factory = new ProxyFactory(Collections.emptyList());

            Class<? extends AnnotatedTarget> proxyClass =
                    factory.createProxyClass(AnnotatedTarget.class, intercepted);

            assertNotNull(proxyClass.getAnnotation(MarkerType.class),
                    "the filter must remove only @ProxyOf, not the target's own annotations");
            assertNotNull(proxyClass.getAnnotation(ProxyOf.class));
            assertEquals(AnnotatedTarget.class, proxyClass.getAnnotation(ProxyOf.class).value());
        }
    }
}

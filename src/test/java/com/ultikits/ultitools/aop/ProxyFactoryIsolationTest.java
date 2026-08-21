package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for proxying a class that lives in a foreign class loader.
 * <p>
 * At runtime, module beans are loaded by the UltiTools URLClassLoader over
 * plugins/*.jar while the framework itself is loaded by Paper's plugin loader,
 * so the proxy factory must work across that boundary. This scenario was never
 * covered before issue #188 and is the reason the AOP engine went unverified.
 * <p>
 * The proxy is inheritance-based (issue #190), so these tests build the proxy class with
 * {@code createProxyClass} and instantiate it directly - there is no separate target instance
 * to compare against.
 */
@DisplayName("ProxyFactory Cross-ClassLoader Tests")
class ProxyFactoryIsolationTest {

    /**
     * Loads IsolatedProxyTarget in a class loader that does NOT delegate to the
     * application class loader, so the returned Class is distinct from the one
     * this test class was compiled against.
     */
    private URLClassLoader newIsolatedLoader() throws Exception {
        URL classesRoot = IsolatedProxyTarget.class
                .getProtectionDomain().getCodeSource().getLocation();
        return new URLClassLoader(new URL[]{classesRoot},
                ClassLoader.getSystemClassLoader().getParent());
    }

    @Test
    @DisplayName("Should load target in a genuinely separate class loader")
    void shouldLoadTargetInSeparateClassLoader() throws Exception {
        try (URLClassLoader isolated = newIsolatedLoader()) {
            Class<?> isolatedClass = isolated.loadClass(IsolatedProxyTarget.class.getName());

            // Same name, different Class object - proves the isolation is real.
            assertEquals(IsolatedProxyTarget.class.getName(), isolatedClass.getName());
            assertNotSame(IsolatedProxyTarget.class, isolatedClass);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Should proxy a class loaded by a foreign class loader")
    void shouldProxyClassFromForeignClassLoader() throws Exception {
        try (URLClassLoader isolated = newIsolatedLoader()) {
            Class<Object> isolatedClass =
                    (Class<Object>) isolated.loadClass(IsolatedProxyTarget.class.getName());

            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Set<Method> intercepted = new LinkedHashSet<>(Arrays.asList(
                    isolatedClass.getMethod("getValue"),
                    isolatedClass.getMethod("calculate", int.class, int.class)));
            Class<?> proxyClass = factory.createProxyClass(isolatedClass, intercepted);
            Object proxy = proxyClass.getDeclaredConstructor().newInstance();

            assertNotNull(proxy);
            assertTrue(isolatedClass.isInstance(proxy));

            Object value = isolatedClass.getMethod("getValue").invoke(proxy);
            assertEquals("original", value);

            Object sum = isolatedClass.getMethod("calculate", int.class, int.class)
                    .invoke(proxy, 2, 3);
            assertEquals(5, sum);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Should intercept package-private methods across class loaders")
    void shouldInterceptPackagePrivateAcrossClassLoaders() throws Exception {
        try (URLClassLoader isolated = newIsolatedLoader()) {
            Class<Object> isolatedClass =
                    (Class<Object>) isolated.loadClass(IsolatedProxyTarget.class.getName());

            MethodInterceptor prefixing = invocation -> "intercepted:" + invocation.proceed();
            ProxyFactory factory = new ProxyFactory(Collections.singletonList(prefixing));

            Method pkgMethod = isolatedClass.getDeclaredMethod("packagePrivateMethod");
            pkgMethod.setAccessible(true);
            Set<Method> intercepted = Collections.singleton(pkgMethod);

            Object proxy = factory.createProxyClass(isolatedClass, intercepted)
                    .getDeclaredConstructor().newInstance();

            // The INJECTION strategy puts the proxy in the target's own loader and package, so
            // a package-private method is overridable and therefore interceptable.
            assertEquals("intercepted:pkg-original", pkgMethod.invoke(proxy));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Should inject proxy into the target's own class loader")
    void shouldInjectProxyIntoTargetClassLoader() throws Exception {
        try (URLClassLoader isolated = newIsolatedLoader()) {
            Class<Object> isolatedClass =
                    (Class<Object>) isolated.loadClass(IsolatedProxyTarget.class.getName());

            ProxyFactory factory = new ProxyFactory(Collections.emptyList());
            Class<?> proxyClass =
                    factory.createProxyClass(isolatedClass, Collections.emptySet());

            assertEquals(isolatedClass.getClassLoader(), proxyClass.getClassLoader());
        }
    }
}

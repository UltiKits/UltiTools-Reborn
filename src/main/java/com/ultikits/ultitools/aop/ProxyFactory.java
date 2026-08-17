package com.ultikits.ultitools.aop;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Factory for creating subclass-based proxies.
 * <p>
 * Proxies are generated with ByteBuddy as a subclass of the target class, which allows
 * proxying classes that implement no interface (unlike JDK dynamic proxies). The generated
 * class is injected into the target's own class loader so that package-private methods
 * remain interceptable.
 * <p>
 * <b>Why not CGLIB:</b> cglib 3.3.0 initialises {@code ReflectUtils} by calling
 * {@code setAccessible(true)} on {@code ClassLoader.defineClass}, which throws
 * {@code InaccessibleObjectException} on JDK 17+ unless the JVM is started with
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}. A Paper server never has that flag
 * and a plugin cannot add it. See issue #188 for the full decision record.
 * <p>
 * <b>Limitations:</b>
 * <ul>
 *   <li>Cannot proxy final classes</li>
 *   <li>Cannot intercept final methods</li>
 *   <li>Self-invocation (this.method()) bypasses the proxy</li>
 *   <li>The target class must have an accessible no-arg constructor</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class ProxyFactory {

    private final List<MethodInterceptor> interceptors;

    /**
     * Creates a new proxy factory with the given interceptors.
     *
     * @param interceptors the interceptors to apply to proxied methods
     */
    public ProxyFactory(List<MethodInterceptor> interceptors) {
        this.interceptors = interceptors;
    }

    /**
     * Creates a proxy for the given target object.
     *
     * @param target the object to proxy
     * @param <T>    the type of the target
     * @return a proxy instance
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(T target) {
        return createProxy((Class<T>) target.getClass(), target);
    }

    /**
     * Creates a proxy for the given target class and instance.
     *
     * @param targetClass the class to create a proxy for
     * @param target      the actual target instance
     * @param <T>         the type of the target
     * @return a proxy instance
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> targetClass, T target) {
        try {
            Class<? extends T> proxyClass = new ByteBuddy()
                    .subclass(targetClass)
                    // isVirtual() covers inherited Object methods such as hashCode/equals/
                    // toString, which must keep being forwarded to the target to preserve
                    // the identity semantics the CGLIB implementation had.
                    .method(ElementMatchers.isVirtual()
                            .and(ElementMatchers.not(ElementMatchers.isFinalizer())))
                    .intercept(InvocationHandlerAdapter.of(
                            new DelegatingInvocationHandler(target, interceptors)))
                    .make()
                    .load(targetClass.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded();

            return (T) proxyClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create proxy for " + targetClass.getName() +
                    ". Ensure the class is not final and has an accessible constructor.", e);
        }
    }

    /**
     * Internal handler that delegates proxied calls to the {@link MethodInterceptor} chain.
     */
    private static class DelegatingInvocationHandler implements InvocationHandler {

        private final Object target;
        private final List<MethodInterceptor> interceptors;

        DelegatingInvocationHandler(Object target, List<MethodInterceptor> interceptors) {
            this.target = target;
            this.interceptors = interceptors;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object methods and un-intercepted calls go straight to the target.
            if (method.getDeclaringClass() == Object.class
                    || interceptors == null || interceptors.isEmpty()) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }

            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                    target, method, args, interceptors
            );
            return invocation.proceed();
        }
    }
}

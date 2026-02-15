package com.ultikits.ultitools.aop;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Factory for creating CGLIB-based proxies.
 * <p>
 * CGLIB proxies work by generating a subclass of the target class,
 * which allows proxying classes without interfaces (unlike JDK dynamic proxies).
 * <p>
 * <b>Limitations:</b>
 * <ul>
 *   <li>Cannot proxy final classes</li>
 *   <li>Cannot intercept final methods</li>
 *   <li>Self-invocation (this.method()) bypasses the proxy</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class CglibProxyFactory {

    private final List<MethodInterceptor> interceptors;

    /**
     * Creates a new CGLIB proxy factory with the given interceptors.
     *
     * @param interceptors the interceptors to apply to proxied methods
     */
    public CglibProxyFactory(List<MethodInterceptor> interceptors) {
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
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(targetClass);
        enhancer.setCallback(new CglibMethodInterceptor(target, interceptors));
        
        try {
            return (T) enhancer.create();
        } catch (Exception e) {
            // If default constructor creation fails, try with arguments
            throw new RuntimeException("Failed to create CGLIB proxy for " + targetClass.getName() + 
                    ". Ensure the class is not final and has an accessible constructor.", e);
        }
    }

    /**
     * Internal CGLIB method interceptor that delegates to our {@link MethodInterceptor} chain.
     */
    private static class CglibMethodInterceptor implements net.sf.cglib.proxy.MethodInterceptor {

        private final Object target;
        private final List<MethodInterceptor> interceptors;

        CglibMethodInterceptor(Object target, List<MethodInterceptor> interceptors) {
            this.target = target;
            this.interceptors = interceptors;
        }

        @Override
        public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) 
                throws Throwable {
            // Skip Object methods (hashCode, equals, toString, etc.)
            if (method.getDeclaringClass() == Object.class) {
                return methodProxy.invoke(target, args);
            }

            // If no interceptors, invoke directly
            if (interceptors == null || interceptors.isEmpty()) {
                return method.invoke(target, args);
            }

            // Create invocation chain and proceed
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                    target, method, args, interceptors
            );
            return invocation.proceed();
        }
    }
}

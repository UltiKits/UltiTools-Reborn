package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Implementation of {@link MethodInvocation} that uses reflection to invoke methods.
 * <p>
 * Maintains an interceptor chain and invokes them in order before calling the target method.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class ReflectiveMethodInvocation implements MethodInvocation {

    private final Object target;
    private final Method method;
    private final Object[] arguments;
    private final List<MethodInterceptor> interceptors;
    private int currentIndex = 0;

    /**
     * Creates a new reflective method invocation.
     *
     * @param target       the target object
     * @param method       the method to invoke
     * @param arguments    the method arguments
     * @param interceptors the list of interceptors to apply
     */
    public ReflectiveMethodInvocation(Object target, Method method, Object[] arguments,
                                      List<MethodInterceptor> interceptors) {
        this.target = target;
        this.method = method;
        this.arguments = arguments != null ? arguments : new Object[0];
        this.interceptors = interceptors;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public Object[] getArguments() {
        return arguments;
    }

    @Override
    public Object proceed() throws Throwable {
        // If there are more interceptors, invoke the next one
        if (currentIndex < interceptors.size()) {
            MethodInterceptor interceptor = interceptors.get(currentIndex++);
            return interceptor.invoke(this);
        }
        
        // All interceptors have been invoked, call the target method
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}

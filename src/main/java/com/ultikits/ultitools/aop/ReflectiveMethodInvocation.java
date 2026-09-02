package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;

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
    private final Callable<?> superCall;
    private int currentIndex = 0;

    /**
     * Creates a new reflective method invocation whose chain tail reflects on the target.
     *
     * @param target       the target object
     * @param method       the method to invoke
     * @param arguments    the method arguments
     * @param interceptors the list of interceptors to apply
     */
    public ReflectiveMethodInvocation(Object target, Method method, Object[] arguments,
                                      List<MethodInterceptor> interceptors) {
        this(target, method, arguments, interceptors, null);
    }

    /**
     * Creates a new reflective method invocation with an explicit chain tail.
     * <p>
     * Inheritance-based proxies must pass a {@code superCall} that invokes
     * {@code super.method()}. Reflecting on the target would re-enter the proxy through
     * virtual dispatch and recurse until the stack overflows.
     *
     * @param target       the target object
     * @param method       the method to invoke
     * @param arguments    the method arguments
     * @param interceptors the list of interceptors to apply
     * @param superCall    the chain tail, or null to reflect on the target
     */
    public ReflectiveMethodInvocation(Object target, Method method, Object[] arguments,
                                      List<MethodInterceptor> interceptors, Callable<?> superCall) {
        this.target = target;
        this.method = method;
        this.arguments = arguments != null ? arguments : new Object[0];
        this.interceptors = interceptors;
        this.superCall = superCall;
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

        // Chain tail. An inheritance-based proxy supplies a superCall; reflecting on the
        // target instead would recurse into the proxy. See issue #190.
        if (superCall != null) {
            return superCall.call();
        }

        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}

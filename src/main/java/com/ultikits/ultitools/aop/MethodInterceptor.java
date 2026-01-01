package com.ultikits.ultitools.aop;

/**
 * AOP method interceptor interface.
 * <p>
 * Implementations intercept method invocations and can add behavior
 * before, after, or around the target method execution.
 * <p>
 * Similar to Spring's MethodInterceptor but simplified for UltiTools.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@FunctionalInterface
public interface MethodInterceptor {

    /**
     * Intercept the given method invocation.
     * <p>
     * Implementations should call {@link MethodInvocation#proceed()} to continue
     * the interceptor chain or invoke the target method.
     *
     * @param invocation the method invocation context
     * @return the result of the method invocation
     * @throws Throwable if the interceptor or target method throws an exception
     */
    Object invoke(MethodInvocation invocation) throws Throwable;
}

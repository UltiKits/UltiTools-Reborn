package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;

/**
 * Represents a method invocation that can be intercepted.
 * <p>
 * This interface provides access to the target object, method, and arguments,
 * and allows the invocation to proceed through the interceptor chain.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface MethodInvocation {

    /**
     * Gets the target object being invoked.
     *
     * @return the target object
     */
    Object getTarget();

    /**
     * Gets the method being invoked.
     *
     * @return the method
     */
    Method getMethod();

    /**
     * Gets the arguments passed to the method.
     *
     * @return the method arguments, or an empty array if no arguments
     */
    Object[] getArguments();

    /**
     * Proceeds with the next interceptor in the chain or invokes the target method
     * if this is the last interceptor.
     *
     * @return the result of the invocation
     * @throws Throwable if the invocation throws an exception
     */
    Object proceed() throws Throwable;
}

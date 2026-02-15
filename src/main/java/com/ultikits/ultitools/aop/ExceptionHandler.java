package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;

/**
 * Interface for custom exception handlers used with {@link com.ultikits.ultitools.annotations.ExceptionCatch}.
 * <p>
 * Implementations can be registered as beans and referenced by name in the @ExceptionCatch annotation.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface ExceptionHandler {

    /**
     * Handles an exception caught during method execution.
     *
     * @param exception the exception that was caught
     * @param target    the target object whose method threw the exception
     * @param method    the method that threw the exception
     * @param args      the arguments passed to the method
     * @return a replacement return value, or null
     * @throws Throwable if the handler decides to re-throw the exception
     */
    Object handleException(Throwable exception, Object target, Method method, Object[] args) throws Throwable;

    /**
     * Determines if this handler supports handling the given exception type.
     *
     * @param exceptionType the type of exception
     * @return true if this handler can handle the exception
     */
    default boolean supports(Class<? extends Throwable> exceptionType) {
        return true;
    }

    /**
     * Gets the order of this handler. Lower values have higher priority.
     *
     * @return the handler order
     */
    default int getOrder() {
        return 0;
    }
}

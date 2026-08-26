package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;

/**
 * Interface for custom exception handlers used with {@link com.ultikits.ultitools.annotations.ExceptionCatch}.
 * <p>
 * Implementations can be registered as beans and referenced by name in the @ExceptionCatch annotation.
 * <p>
 * <b>What {@code handleException} throwing actually does:</b> anything it throws propagates to the
 * caller of the intercepted method, including a checked {@link Throwable} the intercepted method's
 * own {@code throws} clause does not declare - {@link com.ultikits.ultitools.aop.ExceptionInterceptor}
 * does not catch or wrap it. There is <b>no</b> distinction between a handler that deliberately
 * re-threw and one whose body failed by accident: both reach business code the same way. A handler
 * implementation that assumes an accidental failure falls back to the annotation's default value is
 * wrong about this interface's contract - write {@code handleException} bodies with that in mind, or
 * catch what you do not want to propagate before it leaves this method.
 * <p>
 * The annotation's default value ({@code @ExceptionCatch(defaultValue = ...)}) is used instead of a
 * handler's result only when: no handler is configured, the configured handler bean name cannot be
 * resolved from the container, the resolved bean does not implement this interface, or
 * {@code handleException} returns normally.
 * <p>
 * <b>Self-invocation:</b> a call a bean makes to its own method is intercepted here exactly as an
 * external call is - this codebase's proxy is instantiated directly as the bean (there is no
 * separate delegate target instance), so self-invocation is not the AOP-bypass gap it is for a
 * delegating proxy. See {@link ProxyFactory}'s class javadoc.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public interface ExceptionHandler {

    /**
     * Handles an exception caught during method execution.
     * <p>
     * Whatever this method throws - checked or unchecked, declared on the intercepted method or
     * not - propagates to the caller unconditionally. See the interface javadoc for the full
     * semantics, including why a handler failure and a deliberate re-throw are indistinguishable.
     *
     * @param exception the exception that was caught
     * @param target    the target object whose method threw the exception
     * @param method    the method that threw the exception
     * @param args      the arguments passed to the method
     * @return a replacement return value, or null
     * @throws Throwable if the handler decides to re-throw the exception, or if the handler's own
     *                    body fails - both propagate identically, see the interface javadoc
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

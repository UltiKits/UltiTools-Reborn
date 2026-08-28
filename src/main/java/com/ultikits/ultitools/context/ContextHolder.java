package com.ultikits.ultitools.context;

import org.jetbrains.annotations.ApiStatus;

/**
 * Static holder for accessing the current IoC container context.
 * <p>
 * This provides a convenient way to access the container from places
 * where dependency injection is not available, such as in AOP interceptors.
 * <p>
 * <b>Note:</b> Prefer constructor/field injection when possible. This class
 * is mainly for framework-internal use.
 *
 * @author wisdomme
 * @since 6.2.0
 */
@ApiStatus.Internal
public class ContextHolder {

    private static volatile SimpleContainer context;

    private ContextHolder() {
        // Utility class
    }

    /**
     * Sets the global container context.
     * <p>
     * This should be called once during application startup.
     *
     * @param container the container to set as global context
     */
    public static void setContext(SimpleContainer container) {
        context = container;
    }

    /**
     * Gets the global container context.
     *
     * @return the current container, or null if not set
     */
    public static SimpleContainer getContext() {
        return context;
    }

    /**
     * Gets a bean from the global context.
     * <p>
     * Convenience method equivalent to {@code getContext().getBean(name)}.
     *
     * @param name the bean name
     * @param <T> the expected type
     * @return the bean instance
     * @throws IllegalStateException if context is not set
     */
    @SuppressWarnings("unchecked")
    public static <T> T getBean(String name) {
        if (context == null) {
            throw new IllegalStateException("ContextHolder not initialized. Call setContext() first.");
        }
        return (T) context.getBean(name);
    }

    /**
     * Gets a bean from the global context by type.
     *
     * @param type the bean type
     * @param <T> the expected type
     * @return the bean instance
     * @throws IllegalStateException if context is not set
     */
    public static <T> T getBean(Class<T> type) {
        if (context == null) {
            throw new IllegalStateException("ContextHolder not initialized. Call setContext() first.");
        }
        return context.getBean(type);
    }

    /**
     * Clears the global context.
     * <p>
     * Should be called during application shutdown.
     */
    public static void clear() {
        context = null;
    }
}

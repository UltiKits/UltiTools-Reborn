package com.ultikits.ultitools.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods or classes for automatic exception catching.
 * <p>
 * When a method annotated with @ExceptionCatch throws an exception of the specified type,
 * it will be caught and handled by the AOP framework instead of propagating up.
 * <p>
 * Usage example:
 * <pre>{@code
 * @Service
 * public class UserService {
 *
 *     @ExceptionCatch(value = {NullPointerException.class, IllegalArgumentException.class})
 *     public User findUser(String id) {
 *         return userRepository.findById(id);
 *         // If NPE or IAE is thrown, it will be caught and null returned
 *     }
 *
 *     @ExceptionCatch(silent = true)
 *     public void logAction(String action) {
 *         // Any exception will be silently caught without logging
 *     }
 * }
 * }</pre>
 *
 * @author wisdomme
 * @since 6.2.0
 * @see com.ultikits.ultitools.aop.ExceptionInterceptor
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExceptionCatch {

    /**
     * The exception types to catch.
     * <p>
     * Defaults to catching all exceptions ({@link Exception}).
     *
     * @return the exception types to catch
     */
    Class<? extends Throwable>[] value() default {Exception.class};

    /**
     * Whether to silently catch exceptions without logging.
     * <p>
     * When false (default), caught exceptions will be logged as warnings.
     * When true, exceptions are silently swallowed.
     *
     * @return true if exceptions should be caught silently
     */
    boolean silent() default false;

    /**
     * The name of a custom exception handler bean to use.
     * <p>
     * If specified, the handler bean must implement
     * {@link com.ultikits.ultitools.aop.ExceptionHandler}.
     * The handler will be retrieved from the container by this name.
     *
     * @return the name of the exception handler bean, or empty string for default handling
     */
    String handler() default "";

    /**
     * A default value expression to return when an exception is caught.
     * <p>
     * Supports simple expressions:
     * <ul>
     *   <li>"null" - returns null</li>
     *   <li>"true" or "false" - returns boolean</li>
     *   <li>Numeric literals - returns the number</li>
     *   <li>"empty" - returns empty collection/array/string</li>
     * </ul>
     * <p>
     * If not specified, the method's return type default value is used
     * (null for objects, 0 for numbers, false for boolean).
     *
     * @return the default value expression
     */
    String defaultValue() default "";
}

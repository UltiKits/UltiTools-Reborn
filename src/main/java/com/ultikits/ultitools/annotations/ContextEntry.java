package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a context connector that is constructed separately by reflection.
 * The target class named by {@link #value()} must not also be registered as a
 * scanned component: context-entry assembly constructs an additional instance,
 * rather than reusing a scanned bean.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/advanced/ioc-container.html">IOC Container</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContextEntry {
    /**
     * @return the separately constructed connector class, not a scanned component
     */
    Class<?> value();
}

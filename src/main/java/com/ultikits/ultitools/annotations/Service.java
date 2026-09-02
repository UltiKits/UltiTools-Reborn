package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Service annotation to replace Spring's @Service.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Service {
    /**
     * Service name.
     *
     * @return service name
     */
    String value() default "";

    /**
     * Service priority. Higher value means higher priority.
     * When multiple implementations of the same interface exist,
     * the one with highest priority will be returned by getBean(Class).
     *
     * @return priority value (default 0)
     */
    int priority() default 0;
}

package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AliasFor annotation to replace Spring's @AliasFor.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AliasFor {
    /**
     * Alias for value.
     *
     * @return alias value
     */
    String value() default "";

    /**
     * Annotation type.
     *
     * @return annotation type
     */
    Class<? extends java.lang.annotation.Annotation> annotation() default java.lang.annotation.Annotation.class;

    /**
     * Attribute name.
     *
     * @return attribute name
     */
    String attribute() default "";
}

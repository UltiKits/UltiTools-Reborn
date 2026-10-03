package com.ultikits.ultitools.config.convert;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a converter discovered in a module's scan packages, before config initialization.
 * Converter classes must expose a public no-argument constructor; they are not IoC beans.
 *
 * @since 6.3.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigConverterFor {
    /** @return the Java value class served by the converter */
    Class<?> value();
    /** @return whether only the exact value class, rather than its subtypes, is served */
    boolean exact() default false;
}

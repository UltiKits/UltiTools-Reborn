package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a numeric config value falls within the specified range.
 * If the value is out of range, the module refuses to load naming the field, the actual value,
 * and the violated bounds - the config file is never rewritten (D-01).
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
    /** Minimum allowed value (inclusive). Default: -Double.MAX_VALUE */
    double min() default -Double.MAX_VALUE;

    /** Maximum allowed value (inclusive). Default: Double.MAX_VALUE */
    double max() default Double.MAX_VALUE;
}

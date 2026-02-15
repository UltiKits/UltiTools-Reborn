package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a numeric config value falls within the specified range.
 * If the value is out of range, it is reset to the field's default value and a warning is logged.
 * <p>
 * 验证数值配置值是否在指定范围内。如果值超出范围，将重置为字段默认值并记录警告。
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

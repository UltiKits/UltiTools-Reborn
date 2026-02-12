package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a Collection or String config value has a size/length within the specified bounds.
 * If the size is out of bounds, the value is reset to the field's default and a warning is logged.
 * <p>
 * 验证集合或字符串配置值的大小/长度在指定范围内。如果大小超出范围，将重置为字段默认值并记录警告。
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Size {
    /** Minimum size (inclusive). Default: 0 */
    int min() default 0;

    /** Maximum size (inclusive). Default: Integer.MAX_VALUE */
    int max() default Integer.MAX_VALUE;
}

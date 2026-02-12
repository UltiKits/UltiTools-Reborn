package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String config value is not null or empty (after trimming).
 * If the value is empty, it is reset to the field's default value and a warning is logged.
 * <p>
 * 验证字符串配置值不为null或空（去除首尾空格后）。如果值为空，将重置为字段默认值并记录警告。
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmpty {
}

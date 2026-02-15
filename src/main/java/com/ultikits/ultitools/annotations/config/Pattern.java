package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String config value matches the specified regular expression.
 * If the value does not match, it is reset to the field's default value and a warning is logged.
 * <p>
 * 验证字符串配置值是否匹配指定的正则表达式。如果不匹配，将重置为字段默认值并记录警告。
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pattern {
    /** The regular expression pattern to match against. */
    String regex();
}

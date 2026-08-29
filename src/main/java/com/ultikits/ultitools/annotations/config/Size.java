package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a Collection or String config value has a size/length within the specified bounds.
 * If the size is out of bounds, the module refuses to load naming the field, the counted size
 * ({@code Collection.size()} or {@code String.length()}), and the violated bounds - the config
 * file is never rewritten (D-01).
 * <p>
 * 验证集合或字符串配置值的大小/长度在指定范围内。如果大小超出范围，模块将拒绝加载，并指出
 * 字段名、统计出的大小（{@code Collection.size()} 或 {@code String.length()}）和被违反的
 * 边界——配置文件绝不会被改写（D-01）。
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

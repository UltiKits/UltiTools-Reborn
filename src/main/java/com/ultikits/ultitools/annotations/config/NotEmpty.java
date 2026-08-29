package com.ultikits.ultitools.annotations.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String config value is not null or empty (after trimming).
 * If the value is empty, the module refuses to load naming the field - the config file is never
 * rewritten (D-01).
 * <p>
 * 验证字符串配置值不为null或空（去除首尾空格后）。如果值为空，模块将拒绝加载并指出字段名
 * ——配置文件绝不会被改写（D-01）。
 *
 * @see com.ultikits.ultitools.annotations.ConfigEntry
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NotEmpty {
}

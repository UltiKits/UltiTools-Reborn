package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Config entity annotation.
 * <p>
 * 配置实体注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/config-file.html">Configuration</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigEntity {
    /**
     * @return config file path <br> 配置文件路径
     */
    String value();
}

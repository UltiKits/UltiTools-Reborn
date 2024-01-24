package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Config entry annotation.
 * <p>
 * 配置项注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/config-file.html">Configuration</a>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigEntry {
    /**
     * @return config entry path <br> 配置项路径
     */
    String path() default "";

    /**
     * @return config entry comment <br> 配置项注释
     */
    String comment() default "";
}

package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configuration annotation to replace Spring's @Configuration.
 * <br>
 * 配置注解，用于替换Spring的@Configuration。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Configuration {
    /**
     * Configuration name.
     * <br>
     * 配置名称。
     *
     * @return configuration name <br> 配置名称
     */
    String value() default "";
}

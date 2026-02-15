package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Autowired annotation to replace Spring's @Autowired.
 * <br>
 * 自动装配注解，用于替换Spring的@Autowired。
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface Autowired {
    /**
     * Whether the dependency is required.
     * <br>
     * 依赖是否是必需的。
     *
     * @return true if required <br> 如果是必需的则返回true
     */
    boolean required() default true;
}

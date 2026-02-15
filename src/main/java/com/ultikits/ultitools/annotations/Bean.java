package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean annotation to replace Spring's @Bean.
 * <br>
 * Bean注解，用于替换Spring的@Bean。
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
    /**
     * Bean name.
     * <br>
     * Bean名称。
     *
     * @return bean name <br> Bean名称
     */
    String[] name() default {};

    /**
     * Bean value (alias for name).
     * <br>
     * Bean值（名称的别名）。
     *
     * @return bean value <br> Bean值
     */
    String[] value() default {};
}

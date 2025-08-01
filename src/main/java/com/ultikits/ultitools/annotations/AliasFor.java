package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AliasFor annotation to replace Spring's @AliasFor.
 * <br>
 * 别名注解，用于替换Spring的@AliasFor。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AliasFor {
    /**
     * Alias for value.
     * <br>
     * 值的别名。
     *
     * @return alias value <br> 别名值
     */
    String value() default "";

    /**
     * Annotation type.
     * <br>
     * 注解类型。
     *
     * @return annotation type <br> 注解类型
     */
    Class<? extends java.lang.annotation.Annotation> annotation() default java.lang.annotation.Annotation.class;

    /**
     * Attribute name.
     * <br>
     * 属性名称。
     *
     * @return attribute name <br> 属性名称
     */
    String attribute() default "";
}

package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Component annotation to replace Spring's @Component.
 * <br>
 * 组件注解，用于替换Spring的@Component。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Component {
    /**
     * Component name.
     * <br>
     * 组件名称。
     *
     * @return component name <br> 组件名称
     */
    String value() default "";
}

package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ComponentScan annotation to replace Spring's @ComponentScan.
 * <br>
 * 组件扫描注解，用于替换Spring的@ComponentScan。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ComponentScan {
    /**
     * Base packages to scan.
     * <br>
     * 要扫描的基础包。
     *
     * @return base packages <br> 基础包
     */
    String[] value() default {};

    /**
     * Base packages to scan.
     * <br>
     * 要扫描的基础包。
     *
     * @return base packages <br> 基础包
     */
    String[] basePackages() default {};

    /**
     * Base package classes to scan.
     * <br>
     * 要扫描的基础包类。
     *
     * @return base package classes <br> 基础包类
     */
    Class<?>[] basePackageClasses() default {};
}

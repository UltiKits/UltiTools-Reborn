package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ComponentScan annotation to replace Spring's @ComponentScan.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ComponentScan {
    /**
     * Base packages to scan.
     *
     * @return base packages
     */
    String[] value() default {};

    /**
     * Base packages to scan.
     *
     * @return base packages
     */
    String[] basePackages() default {};

    /**
     * Base package classes to scan.
     *
     * @return base package classes
     */
    Class<?>[] basePackageClasses() default {};
}

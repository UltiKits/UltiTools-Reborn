package com.ultikits.ultitools.annotations;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@EnableAutoRegister
@I18n
@ComponentScan
@Configuration
public @interface UltiToolsModule {
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] scanBasePackages() default {};

    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};

    @AliasFor(annotation = EnableAutoRegister.class, attribute = "eventListener")
    boolean eventListener() default true;

    @AliasFor(annotation = EnableAutoRegister.class, attribute = "cmdExecutor")
    boolean cmdExecutor() default true;

    @AliasFor(annotation = EnableAutoRegister.class, attribute = "config")
    boolean config() default true;

    @AliasFor(annotation = I18n.class, attribute = "value")
    String[] i18n() default {};
}

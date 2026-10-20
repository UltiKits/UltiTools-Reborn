package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UltiTools module annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/advanced/auto-register.html#utitoolsmodule">@UltiToolsModule</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@EnableAutoRegister
@I18n
@ComponentScan
@Configuration
public @interface UltiToolsModule {
    /**
     * @return scan base packages
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] scanBasePackages() default {};

    /**
     * @return scan base package classes
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};

    /**
     * @return whether auto register event listener
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "eventListener")
    boolean eventListener() default true;

    /**
     * @return whether auto register command executor
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "cmdExecutor")
    boolean cmdExecutor() default true;

    /**
     * @return whether auto register config entity
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "config")
    boolean config() default true;

    /**
     * @return i18n code
     * @see <a href="http://www.lingoes.net/en/translator/langcode.htm">I18n Code</a>
     */
    @AliasFor(annotation = I18n.class, attribute = "value")
    String[] i18n() default {};

    /**
     * Entity classes this module owns that live outside its own JAR -- a shared library JAR, a
     * multi-module build's common artifact, or a plugin suite's shared JAR. The module's own JAR
     * is always scanned for {@code @Table} classes automatically; this attribute is additive to
     * that scan, not a replacement for it. Equivalent to JPA's {@code <jar-file>} persistence-unit
     * element.
     * <p>
     * <b>Validated, not trusted (02-14).</b> Each class must live on this module's own classpath --
     * either its own JAR, or a jar/module not already known to belong to a different,
     * already-discovered module. A class that structurally belongs to a DIFFERENT module (its own
     * JAR, or one already recorded as owned by another module) fails plugin registration with a
     * {@code com.ultikits.ultitools.exceptions.PluginModuleException} instead of silently granting
     * this module a working {@code DataOperator} for another module's entity.
     *
     * @return additional entity classes
     * @since 6.3.0
     */
    Class<?>[] additionalEntities() default {};
}

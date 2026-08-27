package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UltiTools module annotation.
 * <p>
 * UltiTools 模块注解。
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
     * @return scan base packages <br> 扫描基础包
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] scanBasePackages() default {};

    /**
     * @return scan base package classes <br> 扫描基础包类
     */
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};

    /**
     * @return whether auto register event listener <br> 是否自动注册事件监听器
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "eventListener")
    boolean eventListener() default true;

    /**
     * @return whether auto register command executor <br> 是否自动注册命令执行器
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "cmdExecutor")
    boolean cmdExecutor() default true;

    /**
     * @return whether auto register config entity <br> 是否自动注册配置实体
     */
    @AliasFor(annotation = EnableAutoRegister.class, attribute = "config")
    boolean config() default true;

    /**
     * @return i18n code <br> 国际化代码
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
     * 本模块拥有、但存放在自身 JAR 之外的实体类——共享库 JAR、多模块构建的公共产物，或插件套件的
     * 共享 JAR。模块自身 JAR 中的 {@code @Table} 类始终会被自动扫描；本属性是对该扫描结果的补充，
     * 而非替代。等价于 JPA 持久化单元中的 {@code <jar-file>} 元素。
     *
     * @return additional entity classes <br> 额外的实体类
     * @since 6.3.0
     */
    Class<?>[] additionalEntities() default {};
}

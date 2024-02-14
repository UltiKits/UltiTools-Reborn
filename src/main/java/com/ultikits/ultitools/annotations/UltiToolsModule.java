package com.ultikits.ultitools.annotations;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AliasFor;

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
}

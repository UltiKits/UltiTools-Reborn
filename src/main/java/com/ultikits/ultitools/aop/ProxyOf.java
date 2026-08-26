package com.ultikits.ultitools.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a generated proxy class and names the target class it proxies.
 * <p>
 * Attached by {@link ProxyFactory#createProxyClass} at generation time, and read back by
 * {@link ProxyFactory#isProxyClass(Class)} and {@link ProxyFactory#unwrap(Class)}. This is the
 * single source of truth for proxy identity in this codebase: a proxy declares what it proxies
 * instead of a consumer inferring it from the generated class's name - a third-party library's
 * default naming convention, which has already changed underneath this codebase once, when the
 * proxy engine moved from CGLIB to ByteBuddy.
 * <p>
 * {@code RUNTIME} retention is required: the generated proxy is injected into the target's own
 * class loader (see {@link ProxyFactory}'s class javadoc), and consumers in other packages -
 * {@code manager.TaskManager}, {@code aop.ExceptionInterceptor},
 * {@code context.FinalContractValidator} - read this annotation reflectively off a bare
 * {@code Class} object, long after compile time.
 * <p>
 * This annotation is framework-internal bookkeeping attached by {@link ProxyFactory} itself; a
 * module author never writes it directly.
 * <p>
 * 由 {@link ProxyFactory#createProxyClass} 在生成代理时附加，{@link ProxyFactory#isProxyClass(Class)}
 * 与 {@link ProxyFactory#unwrap(Class)} 读取它。这是本代码库中代理身份的唯一真源：代理自己声明
 * 被代理的目标类，而不是由消费者从生成类的名称——第三方库的默认命名约定——推断；这个命名约定
 * 已经在代理引擎从 CGLIB 迁移到 ByteBuddy 时变化过一次。本注解是 {@link ProxyFactory} 自身附加的
 * 框架内部记录，模块作者不会直接编写它。
 *
 * @author wisdomme
 * @since 6.3.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProxyOf {

    /**
     * The class this generated proxy subclasses and delegates to.
     *
     * @return the proxied target class
     */
    Class<?> value();
}

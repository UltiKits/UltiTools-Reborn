package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Context entry annotation.
 * <p>
 * 上下文项注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/advanced/ioc-container.html">IOC Container</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContextEntry {
    /**
     * @return context entry class <br> 上下文项类
     */
    Class<?> value();
}

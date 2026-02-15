package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be called before the bean is destroyed.
 * <br>
 * 标记一个方法在Bean销毁前调用。
 * <p>
 * This annotation is applied to a method that needs to be executed before the bean
 * is removed from the container.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreDestroy {
}

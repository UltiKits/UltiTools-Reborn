package com.ultikits.ultitools.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be called after dependency injection is complete.
 * <br>
 * 标记一个方法在依赖注入完成后调用。
 * <p>
 * This annotation is applied to a method that needs to be executed after the bean
 * has been initialized and all dependencies have been injected.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostConstruct {
}

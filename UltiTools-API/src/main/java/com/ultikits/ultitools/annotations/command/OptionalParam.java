package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Not in use!
 * Optional parameter annotation.
 * <p>
 * 请勿使用！
 * 可选参数注解。
 */
@Deprecated
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalParam {
}

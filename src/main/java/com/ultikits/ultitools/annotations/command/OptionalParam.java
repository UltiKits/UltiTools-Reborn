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
 *
 * @deprecated Never implemented — no code reads this annotation, so applying it has no
 *             effect on command parsing. There is no replacement: declare a separate
 *             {@code @CmdMapping} format for each accepted argument shape.
 *             <p>
 *             从未实现——没有任何代码读取该注解，标注它对指令解析不产生任何影响。
 *             没有替代品：请为每种可接受的参数形态各声明一个 {@code @CmdMapping} 格式。
 * @removeIn 6.3.0
 */
@Deprecated(since = "6.0.7", forRemoval = true)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalParam {
}

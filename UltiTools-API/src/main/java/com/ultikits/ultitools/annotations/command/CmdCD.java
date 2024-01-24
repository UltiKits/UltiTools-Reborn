package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command cooldown annotation.
 * <p>
 * 指令冷却注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#command-cooldown">Command cooldown</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdCD {
    /**
     * @return cooldown time in seconds <br> 冷却时间（秒）
     */
    int value() default 0;
}

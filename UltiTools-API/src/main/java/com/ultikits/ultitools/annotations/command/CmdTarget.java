package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command target annotation.
 * <p>
 * 指令目标注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#sender-limitation">@CmdTarget</a>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdTarget {
    CmdTargetType value();

    enum CmdTargetType {
        PLAYER,
        CONSOLE,
        BOTH
    }
}

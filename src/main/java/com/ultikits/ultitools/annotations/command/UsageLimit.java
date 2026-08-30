package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Usage limit annotation.
 * <p>
 * 指令使用限制注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#execution-lock">@UsageLimit</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UsageLimit {
    /**
     * @return limit type <br> 限制类型
     */
    LimitType value();

    /**
     * Whether a console sender is subject to this limit, the same as a player sender.
     * Defaults to {@code true} as of 6.3.0 -- a console sender is included unless a mapping
     * opts out explicitly. <br>
     * 控制台发送者是否与玩家发送者一样受此限制约束。自 6.3.0 起默认值为 {@code true}——除非映射显式排除，
     * 否则控制台默认也受限制。
     *
     * @return whether to contain console <br> 是否包含控制台
     */
    boolean ContainConsole() default true;

    enum LimitType {
        NONE,
        SENDER,
        ALL
    }
}

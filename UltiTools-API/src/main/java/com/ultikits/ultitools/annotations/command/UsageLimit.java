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
     * @return whether to contain console <br> 是否包含控制台
     */
    boolean ContainConsole() default false;

    enum LimitType {
        NONE,
        SENDER,
        ALL
    }
}

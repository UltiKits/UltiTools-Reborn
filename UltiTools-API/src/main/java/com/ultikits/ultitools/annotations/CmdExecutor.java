package com.ultikits.ultitools.annotations;

import java.lang.annotation.*;

/**
 * 命令标记注释
 *
 * @author qianmo
 * @version 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CmdExecutor {
    /**
     * @return 归属的功能
     */
    String function() default "";

    /**
     * @return 权限
     */
    String permission();

    /**
     * @return 描述
     */
    String description();

    /**
     * @return 别名
     */
    String alias();
}

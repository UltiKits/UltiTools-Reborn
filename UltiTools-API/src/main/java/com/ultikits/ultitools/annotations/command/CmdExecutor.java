package com.ultikits.ultitools.annotations.command;

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
     * @return 权限
     */
    String permission() default "";

    /**
     * @return 描述
     */
    String description() default "";

    /**
     * @return 别名
     */
    String[] alias();

    /**
     * @return 是否要求OP
     */
    boolean requireOp() default false;
}

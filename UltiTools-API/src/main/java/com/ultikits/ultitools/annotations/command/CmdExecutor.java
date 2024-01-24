package com.ultikits.ultitools.annotations.command;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Command executor annotation.
 * <p>
 * 命令标记注释
 *
 * @author qianmo
 * @version 1.0.0
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface CmdExecutor {

    /**
     * @return permission <br> 权限
     */
    String permission() default "";

    /**
     * @return description <br> 描述
     */
    String description() default "";

    /**
     * @return command alias <br> 别名
     */
    String[] alias();

    /**
     * @return if requires op <br> 是否要求OP
     */
    boolean requireOp() default false;

    /**
     * @return if it is manually register <br> 是否手动注册
     */
    boolean manualRegister() default false;
}

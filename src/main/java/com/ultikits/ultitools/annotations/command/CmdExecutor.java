package com.ultikits.ultitools.annotations.command;

import com.ultikits.ultitools.annotations.Component;

import java.lang.annotation.*;

/**
 * Command executor annotation.
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
     * @return permission
     */
    /**
     * <b>Note on what a player sees when they lack this permission (#383).</b> This value is also
     * registered with Bukkit, so Paper removes the command from an unpermitted player's command
     * tree and answers with a parse error -- the framework's own refusal message is shown to the
     * console only. Declare the permission on {@code @CmdMapping} instead if a player should be
     * told why. See {@code PermissionValidator}'s class javadoc.
     */
    String permission() default "";

    /**
     * @return description
     */
    String description() default "";

    /**
     * @return command alias
     */
    String[] alias();

    /**
     * @return if requires op
     */
    boolean requireOp() default false;

    /**
     * @return if it is manually register
     */
    boolean manualRegister() default false;
}

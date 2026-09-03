package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command mapping annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CmdMapping {
    /**
     * @return command format  <br>
     * For example: if command is "/test" then <br> "" stands for "/test"；<br>"&lt;player&gt;" stands for "/test &lt;player&gt;"； <br>"send &lt;message&gt;" stands for "/test send &lt;message&gt;"
     */
    String format();

    /**
     * @return command permission
     */
    String permission() default "";

    /**
     * @return if command requires op
     */
    boolean requireOp() default false;
}

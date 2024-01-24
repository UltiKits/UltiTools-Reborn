package com.ultikits.ultitools.annotations.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Command mapping annotation.
 * <p>
 * 指令映射注解。
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/cmd-executor.html#quick-start">Command Excutor</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CmdMapping {
    /**
     * @return command format  <br>
     * For example: if command is "/test" then <br> "" stands for "/test"；<br>"&lt;player&gt;" stands for "/test &lt;player&gt;"； <br>"send &lt;message&gt;" stands for "/test send &lt;message&gt;"
     *
     * <br> 指令格式<br>例如：如果指令前缀是"/test"，那么<br>/test 的format是 ""；<br> /test &lt;player&gt; 的format是 "&lt;player&gt;"； <br>/test send &lt;message&gt; 的format是 "send &lt;message&gt;"
     */
    String format();

    /**
     * @return command permission <br> 指令权限
     */
    String permission() default "";

    /**
     * @return if command requires op <br> 指令是否需要op
     */
    boolean requireOp() default false;
}
